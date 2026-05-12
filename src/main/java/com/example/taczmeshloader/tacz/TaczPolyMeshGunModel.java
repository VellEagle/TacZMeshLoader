package com.example.taczmeshloader.tacz;

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
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshGunModel(
            com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO pojo,
            com.tacz.guns.client.resource.pojo.model.BedrockVersion version) {
        super(pojo, version);
    }

    @Override
    public void render(PoseStack poseStack, ItemStack stack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {

        if (!this.hasPolyMesh()) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        if (cachedTexture == null) {
            TimelessAPI.getGunDisplay(stack).ifPresent(display ->
                    cachedTexture = display.getModelTexture()
            );
        }

        if (cachedTexture == null) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        boolean isFixed = (transformType == ItemDisplayContext.FIXED);
        int safeLight = light;
        if (isFixed) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                safeLight = LevelRenderer.getLightColor(mc.level, pos);
            }
        }

        int safeOverlay = isFixed
                ? net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
                : overlay;

        poseStack.pushPose();
        if (isFixed) {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(0.0F));
        }

        boolean useVBO = (transformType == ItemDisplayContext.GROUND || isFixed);
        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // ========== 修正：scope_sight の消滅バグ対策 ==========
        //
        // 【問題の原因】
        // シェーダー有り（ARCompat 加速パス）かつ scope_sight を装着しているとき、
        // BedrockGunModel.renderAccelerated() は銃本体をレイヤー -943+3 で描画し、
        // その RenderBeforeFunction で stencilFunc(GL_GREATER, 127) を登録する。
        //
        // 旧コードは PolyMesh の endBatch() を super.render() より先に呼んでいた。
        // ARCompat 加速パスでは endBatch() の実行タイミングと RenderBeforeFunction の
        // 実行タイミングがずれるため、PolyMesh がステンシルテストの影響を受けてしまい消える。
        //
        // 【修正方針】
        // PolyMesh の描画（頂点書き込み + endBatch）を super.render() の後に行う。
        // super.render() の中で ARCompat のレイヤー処理（scope_sight の RenderBeforeFunction
        // = stencilFunc(GL_GREATER, 127) → その後 RenderAfterFunction = disableStencil）
        // が全て完結する。
        // super.render() が返った後は stencilTest が無効化されているため、
        // PolyMesh を安全に描画できる。
        //
        // PolyMesh の描画は ARCompat が使う -943+3 より後のレイヤー -943+4 に置くことで、
        // 確実に銃本体・アタッチメント全ての ARCompat レイヤー処理より後に実行させる。
        // =====================================================================

        // 1. TacZ 本来の描画（内部で scope_sight のステンシル処理が完結する）
        super.render(poseStack, stack, transformType, renderType, light, overlay);

        // 2. PolyMesh を ARCompat の最終レイヤー(-943+3)より後の -943+4 で描画する。
        //    これにより scope_sight の stencilFunc(GL_GREATER, 127) が適用される前に
        //    描画が確定し、ステンシルテストの影響を受けない。
        final int safeLightFinal = safeLight;
        final int safeOverlayFinal = safeOverlay;
        final boolean useVBOfinal = useVBO;

        if (com.tacz.guns.compat.ar.ARCompat.shouldAccelerate()) {
            // 加速パス：-943+4 レイヤーの RenderBeforeFunction 内で PolyMesh を描画する。
            // このレイヤーが処理される時点では -943+3（銃本体 + stencil の無効化）が
            // 既に完了しているため、ステンシルは安全に無効状態。
            PoseStack snapPose = new PoseStack();
            snapPose.last().pose().set(poseStack.last().pose());
            snapPose.last().normal().set(poseStack.last().normal());
            final ResourceLocation texFinal = cachedTexture;
            final boolean hasTranslucentFinal = this.polyMeshModel.hasTranslucentMeshes();

            com.tacz.guns.compat.ar.ARCompat.setRenderLayer(-943 + 4);
            com.tacz.guns.compat.ar.ARCompat.setRenderBeforeFunction(() -> {
                com.tacz.guns.compat.ar.ARCompat.disableAcceleration();

                int vao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
                com.mojang.blaze3d.vertex.BufferUploader.invalidate();

                mc2.gameRenderer.lightTexture().turnOnLightLayer();

                snapPose.pushPose();
                this.polyMeshModel.renderCutoutOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBOfinal);

                // OculusCompatのフラッシュを優先する
                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texFinal));
                    bufferSource.endBatch(RenderType.entityCutout(texFinal));
                }

                if (hasTranslucentFinal) {
                    this.polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBOfinal);
                    // シェーダー(ARCompat)加速時は遅延フラッシュせず即時フラッシュする
                    if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(texFinal));
                    }
                }

                mc2.gameRenderer.lightTexture().turnOffLightLayer();

                org.lwjgl.opengl.GL30.glBindVertexArray(vao);
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            });

            // -943+4 レイヤーを ARCompat に認識させるトリガー。
            // cubes は空なので実頂点は積まれず、RenderBeforeFunction だけが実行される。
            super.render(poseStack, transformType, renderType, light, overlay);

            com.tacz.guns.compat.ar.ARCompat.resetRenderLayer();
            com.tacz.guns.compat.ar.ARCompat.resetRenderBeforeFunction();
        } else {
            // 非加速パス：super.render() 完了後はステンシル無効化済みなので即 endBatch 可能。
            mc2.gameRenderer.lightTexture().turnOnLightLayer();

            this.polyMeshModel.renderCutoutOnly(poseStack, bufferSource, cachedTexture, safeLightFinal, safeOverlayFinal, useVBOfinal);

            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
            }

            if (this.polyMeshModel.hasTranslucentMeshes()) {
                this.polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, cachedTexture, safeLightFinal, safeOverlayFinal, useVBOfinal);

                // シェーダー環境の場合は直ちにフラッシュさせ、遅延フラッシュを回避する
                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    // OculusCompatによるフラッシュが行われなかった場合でも、
                    // シェーダーMod(Oculus等)が導入されていれば安全のため即時フラッシュする
                    if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(cachedTexture));
                    } else {
                        // バニラ環境の場合はドローコール削減のため遅延フラッシュ
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
                    }
                }
            }

            mc2.gameRenderer.lightTexture().turnOffLightLayer();
        }

        poseStack.popPose();
    }

    // --- 以下、loadPolyMesh や TaczPartAdapter などの既存のコードはそのまま変更なし ---

    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            if (this.polyMeshModel != null) {
                this.polyMeshModel.close();
                this.polyMeshModel = null;
            }
            this.cachedTexture = null;
            this.cachedRootChildren = null;

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

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);

                for (ModelRendererWrapper wrapper : this.modelMap.values()) {
                    if (wrapper != null && wrapper.getModelRenderer() != null) {
                        String name = wrapper.getModelRenderer().name;
                        if (name != null && (name.startsWith("muzzle_flash") || name.startsWith("laser_beam"))) {
                            continue;
                        }
                        wrapper.getModelRenderer().cubes.clear();
                    }
                }

                this.cachedTexture = null;
                this.cachedRootChildren = null;
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "meshy", TaczPolyMeshGunModel::new);
        MESH_LOG.info("[TacZMeshLoader] Registered TacZ model type: meshy");
    }

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
            if (part.children != null) {
                for (BedrockPart c : part.children) cachedChildren.add(new TaczPartAdapter(c));
            }
            return cachedChildren;
        }
        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}