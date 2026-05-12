package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.MeshyModelRegistry;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.bedrock.ModelRendererWrapper;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
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

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshAttachmentModel extends BedrockAttachmentModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation loadedPolyMeshPath = null;  // guards against redundant reloads
    private ResourceLocation cachedTexture     = null;
    private boolean textureResolveFailed = false;         // stops per-frame lookup if index is missing
    private List<IPolyMeshBone> cachedRootChildren = null;
    private boolean isMeshModel = false;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
        MeshyModelRegistry.registerAttachmentModel(this);
    }

    @Override
    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem,
                       PoseStack poseStack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {

        if (polyMeshModel == null || !isMeshModel) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // --- Resolve texture ---
        if (cachedTexture == null && !textureResolveFailed) {
            if (attachmentItem != null) {
                IAttachment iAtt = IAttachment.getIAttachmentOrNull(attachmentItem);
                if (iAtt != null)
                    TimelessAPI.getClientAttachmentIndex(iAtt.getAttachmentId(attachmentItem))
                            .ifPresent(idx -> cachedTexture = idx.getModelTexture());
            } else {
                for (Map.Entry<ResourceLocation, ClientAttachmentIndex> entry
                        : TimelessAPI.getAllClientAttachmentIndex()) {
                    if (entry.getValue().getAttachmentModel() == this) {
                        cachedTexture = entry.getValue().getModelTexture();
                        break;
                    }
                }
            }
            if (cachedTexture == null) textureResolveFailed = true;
        }

        if (cachedTexture == null) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // --- Light / overlay ---
        int safeLight = light;
        if (transformType == ItemDisplayContext.FIXED) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null)
                safeLight = LevelRenderer.getLightColor(mc.level, mc.player.blockPosition());
        }
        int safeOverlay = (transformType == ItemDisplayContext.FIXED)
                ? net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY : overlay;

        boolean isStandalone = (currentGunItem == null || currentGunItem.isEmpty());
        boolean useVBO = (transformType == ItemDisplayContext.GROUND
                || transformType == ItemDisplayContext.FIXED);

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // Scopes and sights use stencil buffers under ARCompat.
        // We defer poly_mesh rendering to layer -943+3 so the stencil is already
        // cleared before our mesh is drawn.
        boolean needsLayeredDeferral = transformType.firstPerson()
                && (isScope() || isSight())
                && com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();

        if (needsLayeredDeferral) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            final int safeLightFinal       = safeLight;
            final int safeOverlayFinal     = safeOverlay;
            final ResourceLocation texFinal = cachedTexture;
            final boolean hasTranslucentFinal = polyMeshModel.hasTranslucentMeshes();

            PoseStack snapPose = new PoseStack();
            snapPose.last().pose().set(poseStack.last().pose());
            snapPose.last().normal().set(poseStack.last().normal());

            com.tacz.guns.compat.ar.ARCompat.setRenderLayer(-943 + 3);
            com.tacz.guns.compat.ar.ARCompat.setRenderBeforeFunction(() -> {
                com.tacz.guns.compat.ar.ARCompat.disableAcceleration();

                int vao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
                com.mojang.blaze3d.vertex.BufferUploader.invalidate();
                mc2.gameRenderer.lightTexture().turnOnLightLayer();

                snapPose.pushPose();
                polyMeshModel.renderCutoutOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBO);
                if (hasTranslucentFinal)
                    polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBO);
                snapPose.popPose();

                if (isStandalone) {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texFinal));
                    bufferSource.endBatch(RenderType.entityCutout(texFinal));
                    if (hasTranslucentFinal) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(texFinal));
                        bufferSource.endBatch(RenderType.entityTranslucent(texFinal));
                    }
                } else {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texFinal));
                    bufferSource.endBatch(RenderType.entityCutout(texFinal));
                    if (hasTranslucentFinal)
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(texFinal);
                }

                mc2.gameRenderer.lightTexture().turnOffLightLayer();
                org.lwjgl.opengl.GL30.glBindVertexArray(vao);
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            });

            // Trigger layer -943+3 registration. cubes are empty so no real verts are added.
            super.render(poseStack, transformType, renderType, light, overlay);

            com.tacz.guns.compat.ar.ARCompat.resetRenderLayer();
            com.tacz.guns.compat.ar.ARCompat.resetRenderBeforeFunction();

        } else {
            // Non-accelerated path: stencil is disabled after super.render().
            mc2.gameRenderer.lightTexture().turnOnLightLayer();

            poseStack.pushPose();
            polyMeshModel.renderCutoutOnly(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            boolean hasTranslucent = polyMeshModel.hasTranslucentMeshes();
            if (hasTranslucent)
                polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            poseStack.popPose();

            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            if (isStandalone) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
                if (hasTranslucent) {
                    bufferSource.endBatch(RenderType.entityTranslucentCull(cachedTexture));
                    bufferSource.endBatch(RenderType.entityTranslucent(cachedTexture));
                }
            } else {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
                if (hasTranslucent)
                    com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
            }

            mc2.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    public ResourceLocation getLoadedPolyMeshPath() { return loadedPolyMeshPath; }

    /** Resets the cached path so the next checkTextureAndModel call will re-load geometry (e.g. F3+T). */
    public void invalidatePolyMesh() {
        loadedPolyMeshPath   = null;
        textureResolveFailed = false;
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }
    public int getPolyMeshVertexCount() { return polyMeshModel != null ? polyMeshModel.getTotalVertexCount() : 0; }
    public int getPolyMeshVboCacheSize() { return polyMeshModel != null ? polyMeshModel.getVboCacheSize() : 0; }

    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            if (polyMeshModel != null) polyMeshModel.close();

            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(modelLocation).orElseThrow();

            try (var reader = new InputStreamReader(resource.open())) {
                JsonObject rawJson = JsonParser.parseReader(reader).getAsJsonObject();

                // Check for poly_mesh via proper JSON traversal instead of string search.
                isMeshModel = hasPolyMeshInJson(rawJson);

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

                if (isMeshModel) {
                    List<String> preserveBones = List.of("scope_body", "ocular_ring", "division");
                    for (ModelRendererWrapper wrapper : modelMap.values()) {
                        if (wrapper == null || wrapper.getModelRenderer() == null) continue;
                        String name = wrapper.getModelRenderer().name;
                        boolean isOcular   = name != null && name.startsWith("ocular");
                        boolean isPreserved = name != null && preserveBones.contains(name);
                        if (!isOcular && !isPreserved)
                            wrapper.getModelRenderer().cubes.clear();
                    }
                }

                cachedTexture        = null;
                textureResolveFailed = false;
                cachedRootChildren   = null;
                loadedPolyMeshPath   = modelLocation;
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyLoader] loadPolyMesh failed for attachment: {}", modelLocation, e);
        }
    }

    /**
     * Walks the {@code minecraft:geometry[0].bones} array and returns {@code true}
     * if any bone contains a {@code poly_mesh} key. More robust than string search.
     */
    private static boolean hasPolyMeshInJson(JsonObject rawJson) {
        try {
            JsonArray geometries = rawJson.getAsJsonArray("minecraft:geometry");
            if (geometries == null || geometries.isEmpty()) return false;
            JsonArray bones = geometries.get(0).getAsJsonObject().getAsJsonArray("bones");
            if (bones == null) return false;
            for (JsonElement bone : bones)
                if (bone.getAsJsonObject().has("poly_mesh")) return true;
        } catch (Exception ignored) {}
        return false;
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