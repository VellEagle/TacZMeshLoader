package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.model.IFunctionalRenderer;
import com.tacz.guns.client.model.GunModelConstant;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
    /** LODモデル用にテクスチャを固定する場合にセット。nullなら通常通りTimelessAPIから取得。 */
    private ResourceLocation overrideTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;
    /** additional_magazine アニメーション中は true。magazine サブツリーを通常描画から除外するために使う。 */
    private boolean additionalMagRendering = false;

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
            // poly_mesh が一切ない場合は通常のキューブ描画に完全委譲
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        if (cachedTexture == null) {
            if (overrideTexture != null) {
                cachedTexture = overrideTexture;
            } else {
                TimelessAPI.getGunDisplay(stack).ifPresent(display ->
                        cachedTexture = display.getModelTexture()
                );
            }
        }

        if (cachedTexture == null) {
            // テクスチャが取れない場合もキューブ描画は続ける
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        int safeLight = light;
        int safeOverlay = overlay;

        poseStack.pushPose();

        boolean useVBO = true; // 全コンテキストでVBOを使用
        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // ========== キューブ + メッシュ混在モデルの描画方針 ==========
        //
        // super.render() が担当:
        //   - cubes を持つボーンの描画（poly_mesh との混在ボーンも含む）
        //   - アタッチメント、マズルフラッシュ、レーザー等の機能ボーン
        //   - ARCompat 加速パス全体の制御
        //
        // PolyMesh 描画が担当:
        //   - poly_mesh を持つボーンのメッシュ頂点
        //   - super.render() 完了後（ステンシル処理が全て終わった後）に描画
        //
        // cubes の消去は一切行わない。
        // poly_mesh のみのボーンは geo.json 上で cubes が空のため TacZ は何も描画しない。
        // 混在ボーン（cubes + poly_mesh 両方）は TacZ がキューブを、
        // PolyMesh がメッシュを描画し、両者が正しく合わさる。
        // ================================================================

        // 1. TacZ 本来の描画（キューブボーン + 機能ボーン + scope_sight ステンシル処理）
        super.render(poseStack, stack, transformType, renderType, light, overlay);

        // additional_magazine アニメーション中は magazine サブツリーを
        // FunctionalRenderer 内で描画するため、通常パスでは除外する
        if (additionalMagRendering) {
            polyMeshModel.setExcludeSubtree(GunModelConstant.MAG_NORMAL_NODE);
        } else {
            polyMeshModel.clearExcludeSubtree();
        }

        // 2. PolyMesh を ARCompat の最終レイヤー(-943+3) より後の -943+4 で描画する。
        final int safeLightFinal = safeLight;
        final int safeOverlayFinal = safeOverlay;
        final boolean useVBOfinal = useVBO;

        if (com.tacz.guns.compat.ar.ARCompat.shouldAccelerate()) {
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

                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(RenderType.entityCutoutNoCull(texFinal));
                    bufferSource.endBatch(RenderType.entityCutout(texFinal));
                }

                if (hasTranslucentFinal) {
                    this.polyMeshModel.renderTranslucentOnly(snapPose, bufferSource, texFinal, safeLightFinal, safeOverlayFinal, useVBOfinal);
                    if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(texFinal));
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
            mc2.gameRenderer.lightTexture().turnOnLightLayer();

            this.polyMeshModel.renderCutoutOnly(poseStack, bufferSource, cachedTexture, safeLightFinal, safeOverlayFinal, useVBOfinal);

            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
            }

            if (this.polyMeshModel.hasTranslucentMeshes()) {
                this.polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, cachedTexture, safeLightFinal, safeOverlayFinal, useVBOfinal);

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

    /**
     * geo.json を読み込み、poly_mesh ボーンを PolyMeshModel に登録する。
     *
     * <h3>キューブ・メッシュ混在対応</h3>
     * cubes の消去は一切行わない。理由は次の通り:
     * <ul>
     *   <li><b>poly_mesh のみ</b>のボーンは geo.json 上で cubes が元々空。
     *       TacZ 側は何も描画しないため PolyMesh との二重描画は起きない。</li>
     *   <li><b>cubes のみ</b>のボーンは PolyMeshModel の meshMap に存在しないため
     *       PolyMesh 側は何もしない。TacZ が正常にキューブを描画する。</li>
     *   <li><b>両方を持つ混在ボーン</b>は TacZ がキューブを、PolyMesh がメッシュを
     *       それぞれ描画し、両者が正しく合わさる。cubes を消す必要はない。</li>
     * </ul>
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

                MESH_LOG.info("[MeshyLoader] Loaded poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /** LODモデル用テクスチャを固定する。checkLod介入時に呼ぶ。 */
    public void setOverrideTexture(ResourceLocation texture) {
        this.overrideTexture = texture;
        this.cachedTexture = null; // キャッシュをリセット
    }

    /**
     * additional_magazine の FunctionalRenderer をオーバーライドし、
     * キューブ描画後に poly_mesh も同じ poseStack（= additional_magazine のtransform済み）で描画する。
     *
     * cubeモデルでは renderAdditionalMagazine が additional_magazine のtransform下で
     * magazineNode のキューブを直接 compile() するが、poly_mesh は PolyMeshModel の
     * 独自ツリーで描画するため additional_magazine のtransformが無視される。
     * このオーバーライドでその問題を解決する。
     */
    @Override
    public void setFunctionalRenderer(String node,
            java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                    IFunctionalRenderer> function) {
        super.setFunctionalRenderer(node, function);

        if (GunModelConstant.MAG_ADDITIONAL_NODE.equals(node)) {
            // super が登録した FunctionalRenderer をラップして poly_mesh 描画を追加する
            com.tacz.guns.client.model.bedrock.ModelRendererWrapper wrapper =
                    modelMap.get(GunModelConstant.MAG_ADDITIONAL_NODE);
            if (wrapper == null) return;

            com.tacz.guns.client.model.bedrock.BedrockPart part = wrapper.getModelRenderer();
            if (!(part instanceof com.tacz.guns.client.model.FunctionalBedrockPart functionalPart)) return;

            java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                    IFunctionalRenderer> original = functionalPart.functionalRenderer;
            if (original == null) return;

            functionalPart.functionalRenderer = (bp) -> {
                IFunctionalRenderer originalRenderer = original.apply(bp);
                return (poseStack, vertexBuffer, transformType, light, overlay) -> {
                    // additional_magazine 描画中フラグをセット
                    additionalMagRendering = true;
                    // 1. 元の描画（キューブ）
                    if (originalRenderer != null) {
                        originalRenderer.render(poseStack, vertexBuffer, transformType, light, overlay);
                    }
                    additionalMagRendering = false;
                    // 2. magazine サブツリーのpoly_meshのみを
                    //    additional_magazine のtransform済み poseStack で描画
                    // （全体を再描画すると銃本体が二重描画されるため magazine 以下のみ）
                    if (hasPolyMesh() && cachedTexture != null
                            && polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_NORMAL_NODE)) {
                        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                        net.minecraft.client.renderer.MultiBufferSource.BufferSource bufferSource =
                                mc.renderBuffers().bufferSource();
                        mc.gameRenderer.lightTexture().turnOnLightLayer();

                        polyMeshModel.renderSubtree(GunModelConstant.MAG_NORMAL_NODE,
                                poseStack, bufferSource, cachedTexture, light, overlay, true);
                        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                            bufferSource.endBatch(net.minecraft.client.renderer.RenderType.entityCutoutNoCull(cachedTexture));
                        }
                        mc.gameRenderer.lightTexture().turnOffLightLayer();
                    }
                };
            };
        }
    }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "mesh", TaczPolyMeshGunModel::new);
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
