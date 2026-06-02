package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    @Override
    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack poseStack,
                       ItemDisplayContext transformType, RenderType renderType, int light, int overlay) {

        if (!this.hasPolyMesh()) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // ---- テクスチャ解決 ----
        if (cachedTexture == null) {
            if (attachmentItem != null) {
                IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
                if (iAttachment != null) {
                    TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachmentItem))
                            .ifPresent(index -> cachedTexture = index.getModelTexture());
                }
            } else {
                for (Map.Entry<ResourceLocation, ClientAttachmentIndex> entry : TimelessAPI.getAllClientAttachmentIndex()) {
                    if (entry.getValue().getAttachmentModel() == this) {
                        cachedTexture = entry.getValue().getModelTexture();
                        break;
                    }
                }
            }
        }

        if (cachedTexture == null) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // ---- ライト・オーバーレイ計算 ----
        int safeLight = light;
        int safeOverlay = overlay;

        boolean isStandalone = (currentGunItem == null || currentGunItem.isEmpty());
        boolean useVBO = true; // 全コンテキストでVBOを使用

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // ========== キューブ + メッシュ混在対応 ==========
        //
        // 銃本体と同じ設計方針:
        //   super.render() がキューブ・ステンシル・ARCompat処理を全て担当し、
        //   PolyMesh はその後に描画する。
        //
        // cubes の消去は一切行わない。
        // poly_mesh のみのボーンは geo.json 上で cubes が元々空。
        // 混在ボーンは TacZ がキューブを、PolyMesh がメッシュをそれぞれ描画する。
        //
        // sight/scope の場合:
        //   BedrockAttachmentModel.render() 末尾で super.render()（BedrockModel.render()）が
        //   呼ばれる時点でステンシル処理は全て完結している。
        //   ARCompat 加速時も TaczPolyMeshGunModel と同様に -943+3/-943+4 レイヤーで対処する。
        // =================================================

        if (com.tacz.guns.compat.ar.ARCompat.shouldAccelerate()
                && transformType.firstPerson()
                && (isScope() || isSight())) {

            // 加速パス（sight/scope + 一人称 + ARCompat）
            // TacZ の使う最終レイヤーは -943+2 なので、+3 はその後に処理される。
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            final int safeLightF  = safeLight;
            final int safeOverlayF = safeOverlay;
            final ResourceLocation texF = cachedTexture;
            final boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();
            final boolean standaloneF = isStandalone;

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
                this.polyMeshModel.renderCutoutOnly(snapPose, bufferSource, texF, safeLightF, safeOverlayF, useVBO);
                if (hasTrans) {
                    this.polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texF, safeLightF, safeOverlayF, useVBO);
                }
                snapPose.popPose();

                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texF));
                    bufferSource.endBatch(RenderType.entityCutout(texF));
                    if (hasTrans) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(texF));
                    }
                }

                mc2.gameRenderer.lightTexture().turnOffLightLayer();

                org.lwjgl.opengl.GL30.glBindVertexArray(vao);
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            });

            // -943+3 レイヤーを ARCompat に認識させるトリガー
            super.render(poseStack, transformType, renderType, light, overlay);

            com.tacz.guns.compat.ar.ARCompat.resetRenderLayer();
            com.tacz.guns.compat.ar.ARCompat.resetRenderBeforeFunction();

        } else if (com.tacz.guns.compat.ar.ARCompat.shouldAccelerate()) {

            // 加速パス（sight/scope 以外）
            // TaczPolyMeshGunModel と同じ -943+4 パターン
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            final int safeLightF  = safeLight;
            final int safeOverlayF = safeOverlay;
            final ResourceLocation texF = cachedTexture;
            final boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();

            PoseStack snapPose = new PoseStack();
            snapPose.last().pose().set(poseStack.last().pose());
            snapPose.last().normal().set(poseStack.last().normal());

            com.tacz.guns.compat.ar.ARCompat.setRenderLayer(-943 + 4);
            com.tacz.guns.compat.ar.ARCompat.setRenderBeforeFunction(() -> {
                com.tacz.guns.compat.ar.ARCompat.disableAcceleration();

                int vao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
                com.mojang.blaze3d.vertex.BufferUploader.invalidate();

                mc2.gameRenderer.lightTexture().turnOnLightLayer();

                snapPose.pushPose();
                this.polyMeshModel.renderCutoutOnly(snapPose, bufferSource, texF, safeLightF, safeOverlayF, useVBO);
                if (hasTrans) {
                    this.polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texF, safeLightF, safeOverlayF, useVBO);
                }
                snapPose.popPose();

                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texF));
                    bufferSource.endBatch(RenderType.entityCutout(texF));
                    if (hasTrans) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(texF));
                    }
                }

                mc2.gameRenderer.lightTexture().turnOffLightLayer();

                org.lwjgl.opengl.GL30.glBindVertexArray(vao);
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            });

            super.render(poseStack, transformType, renderType, light, overlay);

            com.tacz.guns.compat.ar.ARCompat.resetRenderLayer();
            com.tacz.guns.compat.ar.ARCompat.resetRenderBeforeFunction();

        } else {

            // 非加速パス
            // super.render() 完了後はステンシル無効化済みなので安全に endBatch できる
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            mc2.gameRenderer.lightTexture().turnOnLightLayer();

            this.polyMeshModel.renderCutoutOnly(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();
            if (hasTrans) {
                this.polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            }

            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
                if (hasTrans) {
                    if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(cachedTexture));
                    } else {
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
                    }
                }
            }

            mc2.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    /**
     * geo.json を読み込み、poly_mesh ボーンを PolyMeshModel に登録する。
     *
     * cubes の消去は一切行わない（TaczPolyMeshGunModel と同じ方針）。
     */
    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            if (this.polyMeshModel != null) {
                this.polyMeshModel.close();
            }

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

                // cubes の消去は一切行わない（クラス Javadoc 参照）

                this.cachedTexture = null;
                this.cachedRootChildren = null;

                // シェーダー切り替え時に VBO キャッシュを無効化するため登録する
                com.example.taczmeshloader.render.ShaderStateTracker.register(this.polyMeshModel);

                MESH_LOG.info("[MeshyLoader] Loaded attachment poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

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
