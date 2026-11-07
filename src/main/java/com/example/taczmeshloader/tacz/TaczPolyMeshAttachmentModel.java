package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshAttachmentModel extends BedrockAttachmentModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;

    private final Set<String> ocularPolyMeshBoneNames = new HashSet<>();
    private final Set<String> ocularRingPolyMeshBoneNames = new HashSet<>();
    private final Set<String> divisionPolyMeshBoneNames = new HashSet<>();

    private static final java.lang.reflect.Field SCOPE_VIEW_RADIUS_FIELD;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = BedrockAttachmentModel.class.getDeclaredField("scopeViewRadiusModifier");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) {}
        SCOPE_VIEW_RADIUS_FIELD = f;
    }

    private float getScopeViewRadiusModifier() {
        if (SCOPE_VIEW_RADIUS_FIELD != null) {
            try { return SCOPE_VIEW_RADIUS_FIELD.getFloat(this); }
            catch (IllegalAccessException ignored) {}
        }
        return 1.0f;
    }

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    // =========================================================================
    // visibility 復元
    // =========================================================================

    private void restorePartVisibilityForPolyMesh() {
        Set<BedrockPart> divisionLeaves = new HashSet<>();
        if (divisionNodePaths != null) {
            for (List<BedrockPart> path : divisionNodePaths) {
                if (path != null && !path.isEmpty()) divisionLeaves.add(path.get(path.size() - 1));
            }
        }
        restorePathLeaf(scopeBodyPath, divisionLeaves);
        restorePathLeaf(ocularRingPath, divisionLeaves);
        if (ocularNodePaths != null) {
            for (List<BedrockPart> path : ocularNodePaths) restorePathLeaf(path, divisionLeaves);
        }
    }

    private static void restorePathLeaf(@Nullable List<BedrockPart> path, Set<BedrockPart> excluded) {
        if (path == null || path.isEmpty()) return;
        BedrockPart leaf = path.get(path.size() - 1);
        if (!excluded.contains(leaf)) leaf.visible = true;
    }

    /**
     * scope/sight 一人称時の PolyMesh 描画。ステンシルバッファの残留値を利用して穴描画。
     */
    private void renderPolyMeshThroughStencilHole(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        com.tacz.guns.util.RenderHelper.enableItemEntityStencilTest();
        RenderSystem.stencilFunc(GL11.GL_EQUAL, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        // 【TacZ 1.2.0 対応】渡されたバッファのまま書き出す（ステンシル有効中に確定させる）
        if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
            com.example.taczmeshloader.render.MeshyBuffers.endBatch(bufferSource, RenderType.entityCutoutNoCull(tex));
            com.example.taczmeshloader.render.MeshyBuffers.endBatch(bufferSource, RenderType.entityCutout(tex));
        }

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
                com.example.taczmeshloader.render.MeshyBuffers.endBatch(bufferSource, RenderType.entityTranslucentCull(tex));
            }
        }

        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        com.tacz.guns.util.RenderHelper.disableItemEntityStencilTest();
    }

    /**
     * 非 scope/sight 時（通常描画）の PolyMesh 描画。
     */

    private void renderPolyMeshNormalAttachment(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        }
    }

    /**
     * 【TacZ 1.2.0（公式 NeoForge 版）対応】上書きする render を「最終的に必ず通る」9 引数版
     * （不透明度 alpha と描画先バッファ付き）に変更。TacZ 1.2.0 では古い 7 引数版を含む
     * 全ての render がこの版に集約され、銃に付いたアタッチメントの描画
     * （AttachmentRender）もこの版を直接呼ぶため、古い版の上書きでは
     * メッシュが描かれなくなる。
     */
    @Override
    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack poseStack,
                       ItemDisplayContext transformType, RenderType renderType, int light, int overlay,
                       float alpha, MultiBufferSource bufferSource) {

        if (!this.hasPolyMesh()) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay, alpha, bufferSource);
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
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay, alpha, bufferSource);
            return;
        }

        // ---- ライト・オーバーレイ計算 ----
        int safeLight = light;
        int safeOverlay = overlay;

        boolean isStandalone = (currentGunItem == null || currentGunItem.isEmpty());

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態では VBO 直接描画が正しく表示されないことが実機で確認されているため、
        // その場合は VBO を無効化する。
        final boolean isGuiLike = com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();
        // 【TacZ 1.2.0 サーマル対応】サーマルスコープ使用中は、TacZ がエンティティの描画バッファを
        // ThermalBuffers で包んで熱画像用に頂点を写し取る。VBO で直接描くとこれを通らず
        // 熱画像にメッシュが写らないため、その間だけ通常の描画経路（バッファ経由）で描く。
        final boolean useVBO = !isGuiLike
                && !(bufferSource instanceof com.tacz.guns.client.renderer.scope.ScopeThermalRenderer.ThermalBuffers);
        final boolean isFirstPersonScopeOrSight = transformType.firstPerson() && isScope();
        // TacZ は一人称で scope または sight のとき、ocular をステンシル処理の中でのみ描画し、
        // 通常描画では描かない。メッシュの ocular も同じ扱いにするため、この条件の間は
        // 通常のメッシュ描画パスから ocular 系ボーンを除外する
        // （ステンシル処理側の描画は BedrockAttachmentModelMixin → renderStencilPartMesh が担当）。
        final boolean hideOcularMeshes = transformType.firstPerson() && (isScope() || isSight())
                && !ocularPolyMeshBoneNames.isEmpty();
        // ocular_ring も同様。TacZ は一人称スコープ時、ocular_ring を「ステンシル書き込みより前」に
        // 描く（リングが手前にあると、その部分には ocular のステンシルが書き込まれない）。
        // 通常のメッシュ描画パスで後から描くと、ocular のステンシル領域に隠されて消えてしまうため、
        // この条件の間は通常パスから外し、TacZ の描画タイミングで描く（renderStencilPartMesh が担当）。
        final boolean hideOcularRingMeshes = isFirstPersonScopeOrSight
                && !ocularRingPolyMeshBoneNames.isEmpty();

        Minecraft mc2 = Minecraft.getInstance();
        // 描画先は TacZ から渡されたバッファ（bufferSource 引数）をそのまま使う

        // ---------- AR (Accelerated Rendering) との関わり方 ----------
        // TaczPolyMeshGunModel と同じ方針・同じ理由。AR のレイヤー機構経由で
        // 部分的に協調させようとすることに起因する不具合の再発を避けるため、
        // AR が有効な場合はこのアタッチメントの描画全体を AR の介入対象から
        // 完全に外す（一時的に無効化してから、AR 未導入時と同じコードパスで
        // 描画する）。
        final boolean shouldRestoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        try {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay, alpha, bufferSource);
            restorePartVisibilityForPolyMesh();

            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOnLightLayer();
            }

            polyMeshModel.setOcularBonesHidden(hideOcularMeshes);
            polyMeshModel.setOcularRingBonesHidden(hideOcularRingMeshes);
            try {
                if (isFirstPersonScopeOrSight) {
                    renderPolyMeshThroughStencilHole(poseStack, bufferSource, cachedTexture, light, overlay, useVBO);
                } else {
                    renderPolyMeshNormalAttachment(poseStack, bufferSource, cachedTexture, light, overlay, useVBO);
                }
            } finally {
                polyMeshModel.setOcularBonesHidden(false);
                polyMeshModel.setOcularRingBonesHidden(false);
            }
            boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();

            // 【TacZ 1.2.0 対応】書き出しを MeshyBuffers にまとめた（渡されたバッファを使うため）
            com.example.taczmeshloader.render.MeshyBuffers.flushCutout(bufferSource, cachedTexture);
            if (hasTrans) {
                com.example.taczmeshloader.render.MeshyBuffers.flushTranslucent(bufferSource, cachedTexture);
            }

            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOffLightLayer();
            }
        } finally {
            if (shouldRestoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
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

                com.example.taczmeshloader.render.ShaderStateTracker.register(this.polyMeshModel);

                ocularPolyMeshBoneNames.clear();
                if (ocularNodePaths != null) {
                    for (List<BedrockPart> path : ocularNodePaths) {
                        if (path == null || path.isEmpty()) continue;
                        BedrockPart leaf = path.get(path.size() - 1);
                        if (leaf.name != null && polyMeshModel.hasMeshInSubtree(leaf.name)) {
                            ocularPolyMeshBoneNames.add(leaf.name);
                        }
                    }
                }

                ocularRingPolyMeshBoneNames.clear();
                if (ocularRingPath != null && !ocularRingPath.isEmpty()) {
                    BedrockPart ringLeaf = ocularRingPath.get(ocularRingPath.size() - 1);
                    if (ringLeaf.name != null && polyMeshModel.hasMeshInSubtree(ringLeaf.name)) {
                        ocularRingPolyMeshBoneNames.add(ringLeaf.name);
                    }
                }

                divisionPolyMeshBoneNames.clear();
                if (divisionNodePaths != null) {
                    for (List<BedrockPart> path : divisionNodePaths) {
                        if (path == null || path.isEmpty()) continue;
                        BedrockPart leaf = path.get(path.size() - 1);
                        if (leaf.name != null && polyMeshModel.hasMeshInSubtree(leaf.name)) {
                            divisionPolyMeshBoneNames.add(leaf.name);
                        }
                    }
                }

                polyMeshModel.setOcularSubtrees(ocularPolyMeshBoneNames);
                polyMeshModel.setOcularRingSubtrees(ocularRingPolyMeshBoneNames);
                polyMeshModel.setDivisionSubtrees(divisionPolyMeshBoneNames);

                // TaCZ: Scope Overhaul 導入時のみ：メッシュの範囲を TSO に伝える見えないキューブを追加
                if (com.example.taczmeshloader.compat.tso.TsoCompat.isLoaded()) {
                    installTsoMeshBoundsCubes();
                }

                MESH_LOG.info("[MeshyLoader] Loaded attachment poly_mesh from: {} (ocularPolyMeshBones={}, ocularRingPolyMeshBones={}, divisionPolyMeshBones={})",
                        modelLocation, ocularPolyMeshBoneNames, ocularRingPolyMeshBoneNames, divisionPolyMeshBoneNames);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /**
     * 【TacZ 1.2.0 対応】読み込んだファイルに実際にメッシュが含まれていれば true。
     * hasPolyMesh() は「メッシュ読み込み処理が動いたか」しか見ておらず、
     * キューブだけのモデルでも true になるため、こちらで中身まで確認する。
     */
    public boolean hasMeshData() { return polyMeshModel != null && polyMeshModel.hasAnyMesh(); }

    // =========================================================================
    // ocular / ocular_ring / division メッシュ対応（BedrockAttachmentModelMixin から呼ばれる）
    // =========================================================================

    /**
     * 【TacZ 1.2.0 新スコープレンズ対応】TacZ 1.2.0 の新しいレンズ処理（ScopeLensRenderer）は、
     * 接眼部（ocular）の「キューブ」だけを読み取ってレンズの範囲（画面上の位置と大きさ）を決める。
     * 接眼部がメッシュだけのスコープでは範囲がゼロとなり、新しいレンズ表示
     * （切り抜き・歪み・暗視 NVG・サーマル）が丸ごと使われなくなっていた。
     * キューブとメッシュが混在する場合も、範囲が実際より小さく計算されていた。
     *
     * <p>BedrockAttachmentModelMixin から、TacZ が範囲を計算する直前に呼ばれ、
     * メッシュの接眼部の頂点も同じ計算用の受け皿（bounds）に流し込む。
     * bounds は頂点の位置だけを使い、描画は一切行わない。</p>
     */
    public void captureLensMeshBounds(PoseStack poseStack, List<BedrockPart> path, VertexConsumer bounds) {
        if (polyMeshModel == null || path == null || path.isEmpty() || bounds == null) return;
        BedrockPart leaf = path.get(path.size() - 1);
        if (leaf.name == null || !ocularPolyMeshBoneNames.contains(leaf.name)) return;

        poseStack.pushPose();
        try {
            // TacZ の captureLensBounds() と同様に、親パーツまでの変換を適用
            // （対象ボーン自身の変換は PolyMeshModel 側で適用）
            for (int i = 0; i < path.size() - 1; i++) {
                path.get(i).translateAndRotateAndScale(poseStack);
            }
            polyMeshModel.renderSubtreeForStencil(leaf.name, poseStack, bounds,
                    15728880, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * TacZ の BedrockAttachmentModel#renderTempPart() の先頭から呼ばれる。
     *
     * <p>TacZ はスコープ処理の各段階（ocular_ring の描画、ocular のステンシル書き込み、
     * 目鏡の黒マスク、division（レティクル）の描画）で renderTempPart() を使うが、これはキューブしか描けない。
     * ここでメッシュを TacZ のバッファに先に書き込んでおくことで、
     * キューブと同じ結果になるようにする。</p>
     *
     * <ul>
     *   <li>ocular 系：常に描く（TacZ が renderTempPart で ocular を描くのは一人称の時だけ）</li>
     *   <li>division 系：常に描く（TacZ が renderTempPart で division を描くのは一人称の時だけ。
     *       通常描画パスからは常に除外されている）</li>
     *   <li>ocular_ring：一人称の時だけ描く。三人称では TacZ も renderTempPart で
     *       ocular_ring を描くが、そちらは従来通り通常のメッシュ描画パスに任せる
     *       （ここでも描くと二重描画になるため）</li>
     * </ul>
     *
     * <p><b>書き込み先は、TacZ がそのパーツのキューブを描くのに使う RenderType の
     * バッファそのもの</b>にする。TacZ は直後に同じ RenderType でキューブを書き込み、
     * まとめて描画を確定させるので、メッシュはキューブと完全に同じタイミング・
     * 同じ描画設定（シェーダー、テクスチャ、カリング、深度など）で描かれる。</p>
     *
     * <p>【経緯】以前はメッシュを独自の RenderType（entityCutoutNoCull）で描いて
     * その場で確定させていた。この方法だと、メッシュのみのパーツでは TacZ 側の
     * 描画が空になるため、直後の TacZ の処理（スコープの覗き穴の円など）が
     * 「キューブのときとは違う描画設定」のまま実行されてしまう。NeoForge 1.21.1 版の
     * TacZ（1.1.8-hotfix-r6）では、メッシュ化したスコープでのみ覗き穴が開かない
     * 問題が起きており、この違いが原因と考えられる。
     * TacZ の RenderType は裏面カリングありなので、面は両面で書き込む
     * （{@link com.example.taczmeshloader.core.PolyMesh#compileConsumerDoubleSided}）。</p>
     *
     * @param renderType TacZ が renderTempPart() に渡した RenderType
     * @param path TacZ が描画しようとしているパーツのパス（ルート → 対象パーツ）
     */
    public void renderStencilPartMesh(PoseStack poseStack, ItemDisplayContext transformType,
                                      RenderType renderType, List<BedrockPart> path,
                                      int light, int overlay, @Nullable MultiBufferSource stencilBuffer) {
        if (polyMeshModel == null || renderType == null) return;
        if (path == null || path.isEmpty()) return;
        BedrockPart leaf = path.get(path.size() - 1);
        if (leaf.name == null) return;

        final boolean target =
                ocularPolyMeshBoneNames.contains(leaf.name)
                || divisionPolyMeshBoneNames.contains(leaf.name)
                || (ocularRingPolyMeshBoneNames.contains(leaf.name) && transformType.firstPerson());
        if (!target) return;

        // 【TacZ 1.2.0 対応】TacZ が renderTempPart で使うのと同じバッファ（Mixin 経由で受け取る）に書く。
        // 取得できない場合だけ Minecraft 全体のバッファに戻す。
        MultiBufferSource bufferSource = stencilBuffer != null
                ? stencilBuffer : Minecraft.getInstance().renderBuffers().bufferSource();

        poseStack.pushPose();
        // renderTempPart() と同様に、親パーツまでの変換を適用（対象ボーン自身の変換は PolyMeshModel 側で適用）
        for (int i = 0; i < path.size() - 1; i++) {
            path.get(i).translateAndRotateAndScale(poseStack);
        }
        // TacZ が直後に使うのと同じバッファに書き込む（確定は TacZ の renderTempPart() に任せる）
        VertexConsumer vc = bufferSource.getBuffer(renderType);
        polyMeshModel.renderSubtreeForStencil(leaf.name, poseStack, vc, light, overlay);
        poseStack.popPose();
    }

    // =========================================================================
    // TaCZ: Scope Overhaul（TSO）互換
    // =========================================================================

    /**
     * メッシュを持つ ocular / ocular_ring / division パーツに、メッシュと同じ範囲の
     * 見えないキューブ（{@link com.example.taczmeshloader.compat.tso.TsoMeshBoundsCube}）を追加する。
     *
     * <p>TSO はレンズの位置・大きさをパーツのキューブから計算するため、メッシュのみの
     * パーツだと計算できない。見えないキューブは通常何も描かないので、TacZ 本体の描画や
     * TSO 未導入時の動作には影響しない。</p>
     */
    private void installTsoMeshBoundsCubes() {
        if (ocularNodePaths != null) {
            for (List<BedrockPart> path : ocularNodePaths) {
                installTsoMeshBoundsCube(path, ocularPolyMeshBoneNames);
                // TSO のレンズの形の計算を、向きに依存しない計算で補正する対象として登録
                if (path != null && !path.isEmpty()) {
                    com.example.taczmeshloader.compat.tso.TsoCompat.registerOcular(path.get(path.size() - 1));
                }
            }
        }
        installTsoMeshBoundsCube(ocularRingPath, ocularRingPolyMeshBoneNames);
        if (divisionNodePaths != null) {
            for (List<BedrockPart> path : divisionNodePaths) {
                installTsoMeshBoundsCube(path, divisionPolyMeshBoneNames);
            }
        }
    }

    private void installTsoMeshBoundsCube(@Nullable List<BedrockPart> path, Set<String> meshBoneNames) {
        if (path == null || path.isEmpty()) return;
        BedrockPart leaf = path.get(path.size() - 1);
        // 再読み込み時に重複しないよう、以前に追加したものは取り除く
        leaf.cubes.removeIf(c -> c instanceof com.example.taczmeshloader.compat.tso.TsoMeshBoundsCube);
        if (leaf.name == null || !meshBoneNames.contains(leaf.name)) return;

        float[] bounds = polyMeshModel.computeSubtreeBoundsInBoneSpace(leaf.name);
        if (bounds == null) return;
        leaf.cubes.add(new com.example.taczmeshloader.compat.tso.TsoMeshBoundsCube(this, leaf.name, bounds));
        MESH_LOG.debug("[MeshyLoader] TSO bounds cube added: bone={} bounds=[{}, {}, {}] - [{}, {}, {}]",
                leaf.name, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    /**
     * {@link com.example.taczmeshloader.compat.tso.TsoMeshBoundsCube} から、TSO の遅延描画
     * （シェーダー使用時の ocular / division の描き直し）の実行中にだけ呼ばれる。
     * 指定ボーン配下のメッシュを、TSO が用意した VertexConsumer にそのまま書き込む。
     *
     * @param pose 対象ボーン自身の変換まで適用済みの姿勢（キューブを描くときと同じ）
     */
    public void renderTsoDeferredMesh(String boneName, PoseStack.Pose pose, VertexConsumer consumer,
                                      int light, int overlay, float red, float green, float blue, float alpha) {
        if (polyMeshModel == null) return;
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(pose.pose());
        poseStack.last().normal().set(pose.normal());
        polyMeshModel.renderSubtreeInBoneSpace(boneName, poseStack, consumer, light, overlay, red, green, blue, alpha);
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
        @Override public boolean isVisible()     { return true; }
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
