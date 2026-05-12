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
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;

    private boolean isMeshModel = false;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    @Override
    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack poseStack, ItemDisplayContext transformType, RenderType renderType, int light, int overlay) {
        // ========== 修正：sight/scope 取り付け時の消滅バグ対策 ==========
        //
        // 【問題の根本原因】
        // TacZ は sight/scope アタッチメントのレンダリングに「ステンシルバッファ」を使って
        // サイトのレンズ部分をくり抜く処理を行っている。
        //
        // ■ シェーダー無し（非加速パス）の flow:
        //   renderSight() 内で:
        //     1. enableStencil → clearStencil → ocular をステンシルに書き込む
        //     2. stencilFunc(ALWAYS) → disableStencil   ← ここでステンシル無効化
        //     3. super.render()（ベースモデル描画）
        //   → super.render() 後に endBatch() を呼べば安全。
        //
        // ■ シェーダー有り（ARCompat 加速パス）の flow:
        //   renderSightAccelerated() 内で:
        //     1. setRenderLayer(-943)
        //     2. setRenderBeforeFunction(λ):
        //          enableStencil → clearStencil → ocular をステンシルに書き込む
        //          → stencilFunc(ALWAYS) → disableStencil
        //     3. super.render()  ← 頂点をレイヤー -943 のバッチに積む
        //     4. resetRenderLayer / resetRenderBeforeFunction
        //
        //   ARCompat の「加速パス」では、setRenderBeforeFunction で登録したλは
        //   そのレイヤーが実際に GPU へフラッシュされる直前（= endBatch より後）に実行される。
        //   つまり endBatch() → λ実行（ステンシルクリア）の順になる。
        //
        //   前回の修正では「super.render() 後に endBatch()」としたが、
        //   加速パスでは super.render() 内でレイヤー -943 のバッファに積まれた後、
        //   this.render() が返った後もそのバッチは未フラッシュ。
        //   endBatch() を呼んだ瞬間に GPU へ送られるが、その時点ではまだ λ が未実行なので
        //   ステンシルの状態が不定（前フレームの残留や初期値）のまま描画されてしまう。
        //
        // 【修正方針】
        // sight/scope かつ firstPerson かつ ARCompat 加速中の場合は、
        // PolyMesh の描画（頂点書き込み + endBatch）を ARCompat の最終レイヤー
        // (-943 + 2) より後のレイヤー (-943 + 3) に設定した状態で実行する。
        // これにより:
        //   レイヤー -943 の処理（ステンシルクリア→書き込み→無効化）が完了した後に
        //   レイヤー -943+3 の PolyMesh が描画されるため、ステンシルは確実に無効状態。
        //
        // sight/scope でない場合、または非加速・非 firstPerson の場合は
        // 従来通り super.render() 後に endBatch() するだけで問題ない。
        // =====================================================================

        if (this.polyMeshModel == null || !this.isMeshModel) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // ---- テクスチャ解決 ----
        if (cachedTexture == null) {
            if (attachmentItem != null) {
                IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
                if (iAttachment != null) {
                    TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachmentItem)).ifPresent(index -> {
                        cachedTexture = index.getModelTexture();
                    });
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
        if (transformType == ItemDisplayContext.FIXED) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                safeLight = LevelRenderer.getLightColor(mc.level, pos);
            }
        }
        int safeOverlay = (transformType == ItemDisplayContext.FIXED)
                ? net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
                : overlay;

        boolean isStandalone = (currentGunItem == null || currentGunItem.isEmpty());
        boolean useVBO = (transformType == ItemDisplayContext.GROUND || transformType == ItemDisplayContext.FIXED);

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // sight または scope で、かつ一人称視点 + ARCompat 加速中の場合は
        // PolyMesh 描画全体を ARCompat の最終レイヤーより後のレイヤーに委ねる。
        boolean needsLayeredDeferral = transformType.firstPerson()
                && (isScope() || isSight())
                && com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();

        if (needsLayeredDeferral) {
            // Step 1: まず TacZ 本来の描画を実行（内部で -943 〜 -943+2 レイヤーを使用）
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            // Step 2: PolyMesh を -943+3 レイヤーで描画する。
            // TacZ の使う最終レイヤーは -943+2 なので、+3 はそれより後に処理される。
            // この時点でステンシルバッファは既に無効化されているため安全に描画できる。
            final int safeLightFinal = safeLight;
            final int safeOverlayFinal = safeOverlay;
            final ResourceLocation texFinal = cachedTexture;
            final boolean hasTranslucentFinal = this.polyMeshModel.hasTranslucentMeshes();

            // PoseStack のスナップショットを取る（ARCompat パターンに倣う）
            PoseStack snapPose = new PoseStack();
            snapPose.last().pose().set(poseStack.last().pose());
            snapPose.last().normal().set(poseStack.last().normal());

            com.tacz.guns.compat.ar.ARCompat.setRenderLayer(-943 + 3);
            com.tacz.guns.compat.ar.ARCompat.setRenderBeforeFunction(() -> {
                // ここは ARCompat がレイヤー -943+3 をフラッシュする直前に呼ばれる。
                // -943 レイヤーの RenderBeforeFunction（ステンシルクリア→無効化）が
                // 既に完了しているため、この時点でステンシルは安全に無効状態。
                com.tacz.guns.compat.ar.ARCompat.disableAcceleration();

                int vao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
                com.mojang.blaze3d.vertex.BufferUploader.invalidate();

                mc2.gameRenderer.lightTexture().turnOnLightLayer();

                snapPose.pushPose();
                this.polyMeshModel.renderCutoutOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBO);
                if (hasTranslucentFinal) {
                    this.polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBO);
                }
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
                    if (hasTranslucentFinal) {
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(texFinal);
                    }
                }

                mc2.gameRenderer.lightTexture().turnOffLightLayer();

                org.lwjgl.opengl.GL30.glBindVertexArray(vao);
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            });

            // ARCompat に -943+3 レイヤーを認識させるためのトリガー描画。
            // isMeshModel==true のとき cubes は空なので実際の頂点は積まれない。
            // ただし super.render() は再び renderSightAccelerated() に入り無限ループするため、
            // 代わりに最上位の BedrockModel.render() を直接呼ぶ。
            // BedrockModel.render() は cubes の描画のみ行い、sight/scope 処理を含まない。
            // これにより ARCompat が -943+3 レイヤーに「何か積まれた」と認識してフラッシュする。
            super.render(poseStack, transformType, renderType, light, overlay);

            com.tacz.guns.compat.ar.ARCompat.resetRenderLayer();
            com.tacz.guns.compat.ar.ARCompat.resetRenderBeforeFunction();

        } else {
            // ---- 非加速パス（シェーダー無し or 非 firstPerson）----
            // renderSight/renderScope は最後に super.render() を呼んだ後
            // disableItemEntityStencilTest() でステンシルを無効化する。
            // よって super.render() 後に endBatch() すれば安全。

            mc2.gameRenderer.lightTexture().turnOnLightLayer();

            poseStack.pushPose();
            this.polyMeshModel.renderCutoutOnly(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            boolean hasTranslucent = this.polyMeshModel.hasTranslucentMeshes();
            if (hasTranslucent) {
                this.polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            }
            poseStack.popPose();

            // TacZ 本来の描画（ステンシル処理完結まで含む）
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);

            // ステンシル無効化後に endBatch して PolyMesh を GPU へ送信
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
                if (hasTranslucent) {
                    com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
                }
            }

            mc2.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

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

                this.isMeshModel = rawJson.toString().contains("\"poly_mesh\"");

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

                if (this.isMeshModel) {
                    List<String> preserveBones = List.of("scope_body", "ocular_ring", "division");

                    for (ModelRendererWrapper wrapper : this.modelMap.values()) {
                        if (wrapper != null && wrapper.getModelRenderer() != null) {
                            String name = wrapper.getModelRenderer().name;
                            boolean isOcular = name != null && name.startsWith("ocular");
                            boolean isPreserved = name != null && preserveBones.contains(name);

                            if (!isOcular && !isPreserved) {
                                wrapper.getModelRenderer().cubes.clear();
                            }
                        }
                    }
                }

                this.cachedTexture = null;
                this.cachedRootChildren = null;
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED to load attachment mesh: location={}", modelLocation, e);
        }
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