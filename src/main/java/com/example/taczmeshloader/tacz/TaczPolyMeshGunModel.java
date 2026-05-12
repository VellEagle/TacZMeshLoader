package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.MeshyModelRegistry;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.bedrock.ModelRendererWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshGunModel extends com.tacz.guns.client.model.BedrockGunModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation loadedPolyMeshPath = null;  // guards against redundant reloads
    private ResourceLocation cachedTexture = null;
    private boolean textureResolveFailed = false;         // stops per-frame lookup if display is missing
    private List<IPolyMeshBone> cachedRootChildren = null;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshGunModel(
            com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO pojo,
            com.tacz.guns.client.resource.pojo.model.BedrockVersion version) {
        super(pojo, version);
        MeshyModelRegistry.registerGunModel(this);
    }

    @Override
    public void render(PoseStack poseStack, ItemStack stack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {

        if (!hasPolyMesh()) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        if (cachedTexture == null && !textureResolveFailed) {
            TimelessAPI.getGunDisplay(stack).ifPresent(d -> cachedTexture = d.getModelTexture());
            if (cachedTexture == null) textureResolveFailed = true;
        }

        if (cachedTexture == null) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        boolean isFixed = (transformType == ItemDisplayContext.FIXED);
        int safeLight = light;
        if (isFixed) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null)
                safeLight = LevelRenderer.getLightColor(mc.level, mc.player.blockPosition());
        }
        int safeOverlay = isFixed
                ? net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY : overlay;

        poseStack.pushPose();
        if (isFixed) poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(0.0F));

        boolean useVBO = (transformType == ItemDisplayContext.GROUND || isFixed);
        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // Step 1: TacZ standard render — handles scope_sight stencil internally.
        super.render(poseStack, stack, transformType, renderType, light, overlay);

        // Step 2: Render poly_mesh after TacZ finishes so stencil is already disabled.
        // Under ARCompat we use layer -943+4 (after TacZ's highest layer -943+3) to
        // ensure the stencil clear has completed before our mesh is drawn.
        final int safeLightFinal   = safeLight;
        final int safeOverlayFinal = safeOverlay;
        final boolean useVBOfinal  = useVBO;

        if (com.tacz.guns.compat.ar.ARCompat.shouldAccelerate()) {
            PoseStack snapPose = new PoseStack();
            snapPose.last().pose().set(poseStack.last().pose());
            snapPose.last().normal().set(poseStack.last().normal());
            final ResourceLocation texFinal       = cachedTexture;
            final boolean hasTranslucentFinal     = polyMeshModel.hasTranslucentMeshes();

            com.tacz.guns.compat.ar.ARCompat.setRenderLayer(-943 + 4);
            com.tacz.guns.compat.ar.ARCompat.setRenderBeforeFunction(() -> {
                com.tacz.guns.compat.ar.ARCompat.disableAcceleration();

                int vao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
                com.mojang.blaze3d.vertex.BufferUploader.invalidate();

                mc2.gameRenderer.lightTexture().turnOnLightLayer();
                snapPose.pushPose();

                polyMeshModel.renderCutoutOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBOfinal);
                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texFinal));
                    bufferSource.endBatch(RenderType.entityCutout(texFinal));
                }

                if (hasTranslucentFinal) {
                    polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBOfinal);
                    if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource))
                        bufferSource.endBatch(RenderType.entityTranslucentCull(texFinal));
                }

                snapPose.popPose();
                mc2.gameRenderer.lightTexture().turnOffLightLayer();
                org.lwjgl.opengl.GL30.glBindVertexArray(vao);
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            });

            // Trigger layer -943+4 — no actual cubes, just signals ARCompat to flush.
            super.render(poseStack, transformType, renderType, light, overlay);

            com.tacz.guns.compat.ar.ARCompat.resetRenderLayer();
            com.tacz.guns.compat.ar.ARCompat.resetRenderBeforeFunction();
        } else {
            // Non-accelerated path: stencil is already disabled after super.render().
            mc2.gameRenderer.lightTexture().turnOnLightLayer();

            polyMeshModel.renderCutoutOnly(poseStack, bufferSource, cachedTexture, safeLightFinal, safeOverlayFinal, useVBOfinal);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
            }

            if (polyMeshModel.hasTranslucentMeshes()) {
                polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, cachedTexture, safeLightFinal, safeOverlayFinal, useVBOfinal);
                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(cachedTexture));
                    } else {
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
                    }
                }
            }

            mc2.gameRenderer.lightTexture().turnOffLightLayer();
        }

        poseStack.popPose();
    }

    public ResourceLocation getLoadedPolyMeshPath() { return loadedPolyMeshPath; }

    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            if (polyMeshModel != null) polyMeshModel.close();

            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(modelLocation).orElseThrow();

            try (var reader = new InputStreamReader(resource.open())) {
                JsonObject rawJson = JsonParser.parseReader(reader).getAsJsonObject();

                IPolyMeshBone adaptedRoot = new IPolyMeshBone() {
                    @Override public String getName()    { return "meshy_dummy_root"; }
                    @Override public float getPivotX()   { return 0; }
                    @Override public float getPivotY()   { return 0; }
                    @Override public float getPivotZ()   { return 0; }
                    @Override public float getRotX()     { return 0; }
                    @Override public float getRotY()     { return 0; }
                    @Override public float getRotZ()     { return 0; }
                    @Override public boolean isVisible() { return true; }
                    @Override public void applyTransform(PoseStack ps) {}
                    @Override
                    public List<? extends IPolyMeshBone> getChildren() {
                        if (cachedRootChildren != null) return cachedRootChildren;
                        cachedRootChildren = getShouldRender().stream()
                                .map(TaczPartAdapter::new).collect(Collectors.toList());
                        return cachedRootChildren;
                    }
                };

                polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);

                // Clear cube geometry for all bones except special effects.
                for (ModelRendererWrapper wrapper : modelMap.values()) {
                    if (wrapper == null || wrapper.getModelRenderer() == null) continue;
                    String name = wrapper.getModelRenderer().name;
                    if (name != null && (name.startsWith("muzzle_flash") || name.startsWith("laser_beam"))) continue;
                    wrapper.getModelRenderer().cubes.clear();
                }

                cachedTexture        = null;
                textureResolveFailed = false;
                cachedRootChildren   = null;
                loadedPolyMeshPath   = modelLocation;
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyLoader] loadPolyMesh failed: {}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /** Resets the cached path so the next checkTextureAndModel call will re-load geometry (e.g. F3+T). */
    public void invalidatePolyMesh() {
        loadedPolyMeshPath   = null;
        textureResolveFailed = false;
    }

    public int getPolyMeshVertexCount() { return polyMeshModel != null ? polyMeshModel.getTotalVertexCount() : 0; }
    public int getPolyMeshVboCacheSize() { return polyMeshModel != null ? polyMeshModel.getVboCacheSize() : 0; }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "meshy", TaczPolyMeshGunModel::new);
        MESH_LOG.info("[TacZMeshLoader] Registered model type: meshy");
    }

    // -------------------------------------------------------------------------
    // TaczPartAdapter — bridges BedrockPart to IPolyMeshBone
    // -------------------------------------------------------------------------

    private static class TaczPartAdapter implements IPolyMeshBone {
        private final BedrockPart part;
        private List<IPolyMeshBone> cachedChildren;

        TaczPartAdapter(BedrockPart part) { this.part = part; }

        @Override public String getName()        { return part.name == null ? "" : part.name; }
        @Override public float getPivotX()       { return part.x; }
        @Override public float getPivotY()       { return part.y; }
        @Override public float getPivotZ()       { return part.z; }
        @Override public float getRotX()         { return part.xRot; }
        @Override public float getRotY()         { return part.yRot; }
        @Override public float getRotZ()         { return part.zRot; }
        @Override public float getScaleX()       { return part.xScale == 0 ? 1f : part.xScale; }
        @Override public float getScaleY()       { return part.yScale == 0 ? 1f : part.yScale; }
        @Override public float getScaleZ()       { return part.zScale == 0 ? 1f : part.zScale; }
        @Override public boolean isVisible()     { return part.visible; }
        @Override public boolean isIlluminated() { return part.illuminated; }

        @Override
        public List<? extends IPolyMeshBone> getChildren() {
            if (cachedChildren != null) return cachedChildren;
            cachedChildren = new ArrayList<>();
            if (part.children != null)
                for (BedrockPart c : part.children) cachedChildren.add(new TaczPartAdapter(c));
            return cachedChildren;
        }

        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}