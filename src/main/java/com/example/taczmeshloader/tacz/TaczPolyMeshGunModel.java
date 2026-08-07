package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.IFunctionalRenderer;
import com.tacz.guns.client.model.GunModelConstant;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.AnimationListener;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.listener.model.ModelAdditionalMagazineListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshGunModel extends com.tacz.guns.client.model.BedrockGunModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;
    /** LODモデル用にテクスチャを固定する場合にセット。nullなら通常通りTimelessAPIから取得。 */
    private ResourceLocation overrideTexture = null;
    /**
     * MAG_NORMAL_NODE 配下に poly_mesh があるかどうかのキャッシュ（loadPolyMesh 時に確定）。
     * additional_magazine のメッシュ描画要否判定に使う。
     */
    private boolean cachedHasMagMesh = false;
    /**
     * additional_magazine ボーン自体に poly_mesh があるかどうかのキャッシュ。
     * MAG_ADDITIONAL_NODE サブツリーに直接メッシュを持つ場合の判定に使う。
     */
    private boolean cachedHasAdditionalMagMesh = false;

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
            if (overrideTexture != null) {
                cachedTexture = overrideTexture;
            } else {
                TimelessAPI.getGunDisplay(stack).ifPresent(display ->
                        cachedTexture = display.getModelTexture()
                );
            }
        }

        if (cachedTexture == null) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        int safeLight = light;
        int safeOverlay = overlay;

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態では VBO 直接描画が正しく表示されないことが実機で確認されているため、
        // その場合は VBO を無効化する。「メニューが開いているか」ではなく、実際に
        // GUI 描画（Screen#render()）が実行されている「瞬間」だけを検出する
        // （ワールド内の無関係な描画への影響を避けるため）。
        final boolean isGuiLike = com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();
        final boolean useVBO = !isGuiLike;

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        if (cachedHasAdditionalMagMesh) {
            polyMeshModel.setExcludeSubtree(GunModelConstant.MAG_ADDITIONAL_NODE);
        } else {
            polyMeshModel.clearExcludeSubtree();
        }

        // ---------- AR (Accelerated Rendering) との関わり方 ----------
        //
        // 長期にわたる調査の結果（Forge/NeoForge/Fabric 1.20.1/1.21.1 全プラット
        // フォームで検証）、AR のレイヤー機構（setRenderLayer /
        // setRenderBeforeFunction による遅延実行）を介して poly_mesh の
        // 描画とキューブボディの AR 加速を部分的に協調させようとする試みは、
        // プラットフォームごとに異なる形で（スコープのレンズが真っ黒になる、
        // poly_mesh のアニメーションがフリーズする、Iris 環境での FPS 異常など）
        // 繰り返し不具合を引き起こすことが判明した。
        //
        // そのため方針を統一し、AR が有効な場合は、このメッシュ銃の描画全体
        // （キューブボディ・poly_mesh の両方）を AR の介入対象から完全に外す。
        // AR が有効な間だけ一時的に無効化し、AR が全く導入されていない場合と
        // 完全に同じコードパス（常に非加速）で描画する。
        //
        // トレードオフ: メッシュ銃はキューブボディも含めて AR 加速の恩恵を
        // 受けられなくなる。しかし AR とレイヤー機構経由で協調させようとする
        // ことに起因する不具合の再発を避けるため、確実な動作を優先する。
        // （なお、MeshyLoader が一切関与しない純粋なキューブオンリーの銃は
        // これまで通り正常に AR 加速される。）
        final boolean shouldRestoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        try {
            poseStack.pushPose();
            super.render(poseStack, stack, transformType, renderType, light, overlay);

            final ItemStack scopeItem = getCurrentAttachmentItem().get(com.tacz.guns.api.item.attachment.AttachmentType.SCOPE);
            final com.tacz.guns.api.item.IAttachment iAttachment = com.tacz.guns.api.item.IAttachment.getIAttachmentOrNull(scopeItem);
            final boolean hasScope = scopePosPath != null && scopeItem != null && !scopeItem.isEmpty();

            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOnLightLayer();
            }
            if (hasScope && iAttachment != null) {
                if (scopePosPath != null) {
                    poseStack.pushPose();
                    for (com.tacz.guns.client.model.bedrock.BedrockPart p : scopePosPath) {
                        p.translateAndRotateAndScale(poseStack);
                    }
                    com.tacz.guns.client.model.functional.AttachmentRender.renderAttachment(
                            scopeItem, getCurrentGunItem(), poseStack, transformType, safeLight, safeOverlay);
                    poseStack.popPose();
                }
                java.util.Optional<com.tacz.guns.client.resource.index.ClientAttachmentIndex> attachmentIndex =
                        com.tacz.guns.api.TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(scopeItem));
                final boolean[] stencilUsed = {false};
                attachmentIndex.ifPresent(index -> {
                    if (index.isScope() && index.isSight()) {
                        com.tacz.guns.util.RenderHelper.enableItemEntityStencilTest();
                        com.mojang.blaze3d.systems.RenderSystem.stencilFunc(org.lwjgl.opengl.GL11.GL_GREATER, 127, 0xFF);
                        stencilUsed[0] = true;
                    } else if (index.isScope()) {
                        com.tacz.guns.util.RenderHelper.enableItemEntityStencilTest();
                        com.mojang.blaze3d.systems.RenderSystem.stencilFunc(org.lwjgl.opengl.GL11.GL_EQUAL, 0, 0xFF);
                        stencilUsed[0] = true;
                    }
                });
                com.mojang.blaze3d.systems.RenderSystem.stencilOp(
                        org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP);
                renderPolyMeshWithStencil(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
                if (stencilUsed[0]) {
                    com.tacz.guns.util.RenderHelper.disableItemEntityStencilTest();
                }
                com.mojang.blaze3d.systems.RenderSystem.clearStencil(0);
                com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT, Minecraft.ON_OSX);
            } else {
                renderPolyMeshNormal(poseStack, bufferSource, cachedTexture, safeLight, safeOverlay, useVBO);
            }
            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOffLightLayer();
            }
            poseStack.popPose();
        } finally {
            if (shouldRestoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
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

    // =========================================================================
    // PolyMesh 描画ヘルパー
    // =========================================================================

    private void renderPolyMeshWithStencil(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
            bufferSource.endBatch(RenderType.entityCutout(tex));
        }
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityTranslucentCull(tex));
            }
        }
    }

    private void renderPolyMeshNormal(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
            bufferSource.endBatch(RenderType.entityCutout(tex));
        }
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
                if (net.neoforged.fml.ModList.get().isLoaded("iris")) {
                    bufferSource.endBatch(RenderType.entityTranslucentCull(tex));
                } else {
                    com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(tex);
                }
            }
        }
    }

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

                com.example.taczmeshloader.render.ShaderStateTracker.register(this.polyMeshModel);
                cachedHasMagMesh = this.polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_NORMAL_NODE);
                cachedHasAdditionalMagMesh = this.polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_ADDITIONAL_NODE);

                // loadPolyMesh 後に additional_magazine の FunctionalRenderer を再セットアップする。
                // BedrockGunModel のコンストラクタで setFunctionalRenderer が呼ばれた時点では
                // cachedHasMagMesh / cachedHasAdditionalMagMesh がまだ false のため、
                // PolyMesh 描画のフックが適用されていない。
                // poly_mesh が確定した今のタイミングで改めてフックを適用する。
                if (cachedHasMagMesh || cachedHasAdditionalMagMesh) {
                    applyAdditionalMagazineMeshHook();
                }

                MESH_LOG.info("[MeshyLoader] Loaded poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    /**
     * additional_magazine ボーンの FunctionalRenderer に poly_mesh 描画フックを適用する。
     *
     * <p>このメソッドは loadPolyMesh() の末尾から呼ばれる。
     * BedrockGunModel のコンストラクタで設定された FunctionalRenderer の上に、
     * PolyMesh の magazine サブツリー描画を追加でラップする。</p>
     *
     * <h3>描画戦略</h3>
     * <ul>
     *   <li>TacZ オリジナルの FunctionalRenderer（キューブ描画 + visible 制御）を先に実行</li>
     *   <li>additional_magazine の visible が true のとき（アニメーション中）のみ、
     *       続けて poly_mesh の magazine サブツリーを同じ VertexConsumer に書き込む</li>
     *   <li>additional_magazine サブツリー自体に poly_mesh がある場合はそちらも描画</li>
     * </ul>
     */
    private void applyAdditionalMagazineMeshHook() {
        com.tacz.guns.client.model.bedrock.ModelRendererWrapper wrapper =
                modelMap.get(GunModelConstant.MAG_ADDITIONAL_NODE);
        if (wrapper == null) return;

        com.tacz.guns.client.model.bedrock.BedrockPart part = wrapper.getModelRenderer();
        if (!(part instanceof com.tacz.guns.client.model.FunctionalBedrockPart functionalPart)) return;

        // 現在セットされている FunctionalRenderer を取得しておく
        // （BedrockGunModel のコンストラクタが設定した renderAdditionalMagazine ラムダ）
        java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                IFunctionalRenderer> existingFunction = functionalPart.functionalRenderer;

        functionalPart.functionalRenderer = (bp) -> {
            // 既存のレンダラー（TacZ オリジナル: キューブ描画）を取得
            IFunctionalRenderer originalRenderer = (existingFunction != null) ? existingFunction.apply(bp) : null;

            return (poseStack, vertexBuffer, transformType, light, overlay) -> {
                // 1. TacZ オリジナル処理（additional_magazine + magazine キューブ描画）
                if (originalRenderer != null) {
                    originalRenderer.render(poseStack, vertexBuffer, transformType, light, overlay);
                }

                // 2. additional_magazine の visible が true のときのみ poly_mesh を描画する。
                //    visible は ModelAdditionalMagazineListener によってアニメーション再生中に
                //    true にセットされる。false のときは描画しない（TacZ と同じ挙動）。
                if (!bp.visible) return;

                if (hasPolyMesh()) {
                    // 2a. magazine（MAG_NORMAL_NODE）サブツリーの poly_mesh を描画。
                    //     TacZ オリジナルが magazine キューブを複製して描画するのと同様に、
                    //     PolyMesh 側も magazine メッシュを additional_magazine の座標に描画する。
                    if (cachedHasMagMesh) {
                        polyMeshModel.renderSubtreeDirect(
                                GunModelConstant.MAG_NORMAL_NODE, poseStack, vertexBuffer, light, overlay);
                    }
                    // 2b. additional_magazine サブツリー自体の poly_mesh を描画。
                    if (cachedHasAdditionalMagMesh) {
                        polyMeshModel.renderSubtreeDirect(
                                GunModelConstant.MAG_ADDITIONAL_NODE, poseStack, vertexBuffer, light, overlay);
                    }
                }
            };
        };
    }

    /**
     * BedrockGunModel のコンストラクタが MAG_ADDITIONAL_NODE に対して
     * setFunctionalRenderer を呼んだタイミングでは、まだ loadPolyMesh() が
     * 実行されていないため cachedHasMagMesh が false になっている。
     * そのため、ここでは super を呼ぶだけにとどめ、実際のフック適用は
     * loadPolyMesh() 末尾の applyAdditionalMagazineMeshHook() に委ねる。
     */
    @Override
    public void setFunctionalRenderer(String node,
                                      java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                                              IFunctionalRenderer> function) {
        super.setFunctionalRenderer(node, function);
        // loadPolyMesh() 後に再度呼ばれた場合（外部から上書き）は何もしない。
        // フック適用は loadPolyMesh() → applyAdditionalMagazineMeshHook() が担う。
    }

    /**
     * アニメーション用リスナーのサプライ。
     * BedrockGunModel の実装を継承しつつ、additional_magazine ノードに対して
     * {@link MeshAdditionalMagazineListener} を返すことで、
     * poly_mesh モデルの additional_magazine サブツリーの visible も
     * 同時に制御する。
     */
    @Override
    public AnimationListener supplyListeners(String nodeName, ObjectAnimationChannel.ChannelType type) {
        AnimationListener listener = super.supplyListeners(nodeName, type);
        if (listener == null) return null;

        if (GunModelConstant.MAG_ADDITIONAL_NODE.equals(nodeName) && hasPolyMesh()
                && (cachedHasMagMesh || cachedHasAdditionalMagMesh)) {
            // BedrockGunModel.supplyListeners は MAG_ADDITIONAL_NODE に対して
            // すでに ModelAdditionalMagazineListener を返している（BedrockPart.visible を true にする）。
            // ここではさらにそれをラップして、PolyMeshModel 側の除外制御も連動させる。
            return new MeshAdditionalMagazineListener(listener, this);
        }
        return listener;
    }

    /**
     * アニメーションリセット時に additional_magazine poly_mesh の除外設定も
     * cleanAnimationTransform に合わせてリセットする。
     */
    @Override
    public void cleanAnimationTransform() {
        super.cleanAnimationTransform();
        // super.cleanAnimationTransform() が additionalMagazineNode.visible = false にする。
        // PolyMeshModel 側の除外設定は render() の冒頭で毎フレーム再設定するため、
        // ここで明示的にリセットする必要はない。
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /** LODモデル用テクスチャを固定する。checkLod介入時に呼ぶ。 */
    public void setOverrideTexture(ResourceLocation texture) {
        this.overrideTexture = texture;
        this.cachedTexture = null;
    }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "mesh", TaczPolyMeshGunModel::new);
        MESH_LOG.info("[TacZMeshLoader] Registered TacZ model type: meshy");
    }

    // =========================================================================
    // 内部クラス
    // =========================================================================

    /**
     * additional_magazine アニメーションリスナーの PolyMesh 対応版。
     *
     * <p>BedrockGunModel の {@link ModelAdditionalMagazineListener} は
     * {@code BedrockPart.visible = true} にするだけだが、このリスナーは
     * {@link PolyMeshModel#clearExcludeSubtree()} を追加で呼ぶことで、
     * render() 冒頭で設定した除外を解除し、FunctionalRenderer 経由での
     * poly_mesh 描画が正しく動くようにする。</p>
     *
     * <p>実際の poly_mesh 描画は {@link #applyAdditionalMagazineMeshHook()} が
     * セットした FunctionalRenderer 内で行うため、ここでは除外制御のみ担当する。</p>
     */
    private static class MeshAdditionalMagazineListener implements AnimationListener {
        private final AnimationListener delegate;
        private final TaczPolyMeshGunModel model;

        MeshAdditionalMagazineListener(AnimationListener delegate, TaczPolyMeshGunModel model) {
            this.delegate = delegate;
            this.model = model;
        }

        @Override
        public void update(float[] values, boolean blend) {
            delegate.update(values, blend);
            // additional_magazine アニメーション再生中は polyMeshModel の
            // additional_magazine サブツリー除外を解除する。
            // これにより FunctionalRenderer 内の renderSubtreeDirect が機能する。
            if (model.polyMeshModel != null) {
                model.polyMeshModel.clearExcludeSubtree();
            }
        }

        @Override
        public float[] initialValue() { return delegate.initialValue(); }

        @Override
        public ObjectAnimationChannel.ChannelType getType() { return delegate.getType(); }
    }

    /**
     * BedrockPart（TacZ ボーン）を {@link IPolyMeshBone} に適合させるアダプタ。
     */
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