package com.example.taczmeshloader.core;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.mojang.blaze3d.vertex.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;

import java.util.*;
import java.util.function.Function;

public class PolyMeshModel {
    private final IPolyMeshBone root;
    private final Map<String, List<PolyMesh>> meshMap = new HashMap<>();
    private final Set<String> translucentBones = new HashSet<>();
    private final boolean hasTranslucent;
    private final Set<String> meshAncestorBones = new HashSet<>();

    /**
     * 「このライト値については既に meshMap の全ボーンをアップロード済み」を
     * 記録する集合。allVboReady() をボーンツリーの走査無しで O(1) 判定できる
     * ようにするためのもの（詳細は allVboReady/ensureAllUploaded のコメント参照）。
     */
    private final Set<Integer> fullyUploadedLights = new HashSet<>();
    /** poly_mesh を持ち illuminated 扱いになるボーン名セット（祖先伝播考慮済み） */
    private final Set<String> illuminatedBones = new HashSet<>();
    /** 描画から除外するサブツリーのルートボーン名（additional_magazine 対応用） */
    private String excludeSubtreeRoot = null;
    /** excludeSubtreeRoot 配下のボーン名セット（毎回計算しないようキャッシュ） */
    private final Set<String> excludedBones = new HashSet<>();

    /**
     * TacZ のスコープ処理で「TacZ 側の描画タイミングで描く」必要があるボーンのグループ。
     * 各グループは hidden フラグが true の間、通常描画パス（VBO / VertexConsumer 両方）から
     * 除外され、{@link #renderSubtreeForStencil} からのみ描画される。
     *   - ocularBones     : ocular / ocular_sight / ocular_scope（とその配下）
     *   - ocularRingBones : ocular_ring（とその配下）
     *   - divisionBones   : division / division_2 ...（とその配下）。TacZ はキューブの division を
     *                       読み込み時に非表示にし、スコープ処理の中でしか描かないため、
     *                       このグループは常に通常描画パスから除外する。
     */
    private final Set<String> ocularBones = new HashSet<>();
    private final Set<String> ocularRingBones = new HashSet<>();
    private final Set<String> divisionBones = new HashSet<>();
    /**
     * ocular 系・division 系の「ボーン本体」の名前（配下は含まない）。
     * renderSubtreeForStencil で、描画対象の配下に別の ocular / division が
     * ぶら下がっている場合にそれを描かないようにするために使う。
     */
    private final Set<String> specialRootBones = new HashSet<>();
    private boolean ocularBonesHidden = false;
    private boolean ocularRingBonesHidden = false;

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_CULL =
            Util.memoize(RenderType::entityTranslucentCull);

    public PolyMeshModel(IPolyMeshBone root, JsonObject rawJson) {
        this.root = root;
        parsePolyMeshes(rawJson);
        for (String name : meshMap.keySet()) {
            if (name.toLowerCase().contains("translucent")) translucentBones.add(name);
        }
        this.hasTranslucent = !translucentBones.isEmpty();
        buildMeshAncestors(this.root, new ArrayDeque<>());
        buildIlluminatedBones(this.root, false);
    }

    public boolean hasTranslucentMeshes() { return hasTranslucent; }

    private boolean buildMeshAncestors(IPolyMeshBone bone, Deque<String> path) {
        String name = bone.getName();
        path.addLast(name);
        boolean has = meshMap.containsKey(name);
        for (IPolyMeshBone child : bone.getChildren()) if (buildMeshAncestors(child, path)) has = true;
        if (has) meshAncestorBones.addAll(path);
        path.removeLast();
        return has;
    }

    /** illuminated フラグを祖先から子へ伝播させ、poly_mesh を持つボーンを illuminatedBones に登録する */
    private void buildIlluminatedBones(IPolyMeshBone bone, boolean parentIlluminated) {
        boolean illuminated = parentIlluminated || bone.isIlluminated();
        if (illuminated && meshMap.containsKey(bone.getName())) {
            illuminatedBones.add(bone.getName());
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            buildIlluminatedBones(child, illuminated);
        }
    }

    private void parsePolyMeshes(JsonObject rawJson) {
        JsonArray geometries = rawJson.has("minecraft:geometry") ? rawJson.getAsJsonArray("minecraft:geometry") : null;
        if (geometries == null || geometries.isEmpty()) return;
        JsonObject geo = geometries.get(0).getAsJsonObject();
        float texW = geo.getAsJsonObject("description").get("texture_width").getAsFloat();
        float texH = geo.getAsJsonObject("description").get("texture_height").getAsFloat();
        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) return;
        for (JsonElement boneElem : bones) {
            JsonObject boneObj = boneElem.getAsJsonObject();
            if (!boneObj.has("poly_mesh") || !boneObj.has("name")) continue;
            String name = boneObj.get("name").getAsString();
            float pX=0, pY=0, pZ=0;
            if (boneObj.has("pivot")) {
                JsonArray p = boneObj.getAsJsonArray("pivot");
                pX=p.get(0).getAsFloat(); pY=p.get(1).getAsFloat(); pZ=p.get(2).getAsFloat();
            }
            PolyMesh mesh = new PolyMesh(boneObj.getAsJsonObject("poly_mesh"), texW, texH, new float[]{pX,pY,pZ});
            if (mesh.getVertexCount() > 0) {
                // あるメッシュでライト値の VBO が LRU 破棄されたら、そのライト値は
                // 「全メッシュがアップロード済み」ではなくなるので、記録から外す。
                // 次にそのライト値で描画するときは VertexConsumer 経路で描画しつつ
                // 再アップロードされる（ensureAllUploaded）。
                mesh.setEvictionListener(evictedLight -> fullyUploadedLights.remove(evictedLight));
                meshMap.computeIfAbsent(name, k -> new ArrayList<>()).add(mesh);
            }
        }
    }

    // =========================================================================
    // 描画エントリポイント (overlay を受け取るよう修正)
    // =========================================================================

    public void renderWithTranslucentSplit(PoseStack ps, MultiBufferSource buf,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        renderCutoutOnly(ps, buf, tex, light, overlay, useVBO);
        if (hasTranslucent) renderTranslucentOnly(ps, buf, tex, light, overlay, useVBO);
    }

    public void renderCutoutOnly(PoseStack ps, MultiBufferSource buf,
                                 ResourceLocation tex, int light, int overlay, boolean useVBO) {
        if (useVBO && allVboReady(light)) {
            renderVBO(ps, tex, light, false);
        } else {
            if (useVBO) ensureAllUploaded(light);
            VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
            renderBonesConsumer(root, ps, vc, light, overlay, 1f,1f,1f,1f, false);
        }
    }

    public void renderTranslucentOnly(PoseStack ps, MultiBufferSource buf,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        if (!hasTranslucent) return;
        if (useVBO && allVboReady(light)) {
            renderVBO(ps, tex, light, true);
        } else {
            if (useVBO) ensureAllUploaded(light);
            VertexConsumer vc = buf.getBuffer(TRANSLUCENT_CULL.apply(tex));
            renderBonesConsumer(root, ps, vc, light, overlay, 1f,1f,1f,1f, true);
        }
    }

    // =========================================================================
    // VBO 管理
    // =========================================================================

    /**
     * 現在のボーンツリーを辿り、実際に描画され得る（isVisible()==true かつ
     * meshAncestorBones/excludedBones の条件を満たす）poly_mesh ボーン名を
     * 収集する。renderBonesVBO() の枝刈り条件と完全に一致させること。
     *
     * 【最適化の狙い】装着していない弾倉バリエーションなど、モデルによっては
     * 「同時に描画されることのない」代替パーツが多数のボーンに分割されている
     * ことがある。これらは isVisible()==false のため実際には描画されないが、
     * 元のコードは allVboReady() / ensureAllUploaded() で meshMap の
     * 全ボーンを無条件にチェック・アップロードしていたため、ライトレベルが
     * 変わるたびに「現在表示されていないボーン」まで含めて毎回フルアップロード
     * が走っていた。ボーン数が多い（＝装着バリエーションが豊富な）モデルほど
     * この無駄なコストが大きくなる。本来描画されるボーンだけに限定することで、
     * 見た目やボーンの独立した表示切り替えには一切影響を与えずに、この無駄を
     * 削減する。
     */
    /**
     * 現在のライト値について、meshMap の全ボーンが VBO アップロード済みかどうか。
     *
     * 「あるライト値について、一度でも ensureAllUploaded() が完走したことが
     * あるか」を {@link #fullyUploadedLights} で記録するだけにしている。
     * ensureAllUploaded() は常に meshMap の全ボーン（表示/非表示問わず）を
     * 対象にするため、一度完走すれば、以後そのライト値についてはどのボーンが
     * 表示されようと（表示が切り替わろうと）再チェックが不要になる。これにより
     * allVboReady() はボーンツリーを一切走査しない、単純な O(1) の集合参照
     * だけで済む。
     */
    private boolean allVboReady(int light) {
        if (meshMap.isEmpty()) return false;
        return fullyUploadedLights.contains(light);
    }

    private void ensureAllUploaded(int light) {
        for (Map.Entry<String, List<PolyMesh>> entry : meshMap.entrySet()) {
            int bakeLight = illuminatedBones.contains(entry.getKey()) ? 15728880 : light;
            for (PolyMesh m : entry.getValue()) m.ensureUploaded(bakeLight);
        }
        fullyUploadedLights.add(light);
    }


    /**
     * 全 PolyMesh の VBO キャッシュを破棄する。
     *
     * Oculus シェーダー切り替え時など、レンダリング状態が大きく変わった際に
     * {@link com.example.taczmeshloader.render.ShaderStateTracker} から呼ばれる。
     * 次フレームで VBO が再生成されるため、影の反転が解消される。
     */
    public void invalidateVboCache() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.invalidateVboCache();
        }
        fullyUploadedLights.clear();
    }

    // =========================================================================
    // VBO 描画パス
    // =========================================================================

    private void renderVBO(PoseStack ps, ResourceLocation tex, int light, boolean translucentPass) {
        RenderType renderType = translucentPass
                ? TRANSLUCENT_CULL.apply(tex)
                : RenderType.entityCutoutNoCull(tex);

        renderType.setupRenderState();
        renderBonesVBO(root, ps, light, translucentPass);
        renderType.clearRenderState();
    }

    private void renderBonesVBO(IPolyMeshBone bone, PoseStack ps, int light, boolean translucentPass) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;
        if (ocularBonesHidden && ocularBones.contains(bone.getName())) return;
        if (ocularRingBonesHidden && ocularRingBones.contains(bone.getName())) return;
        if (divisionBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        if (isTranslucent == translucentPass) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    mesh.drawVBO(ps.last().pose(), actualLight);
                }
            }
        }

        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesVBO(child, ps, light, translucentPass);
        }

        ps.popPose();
    }

    // =========================================================================
    // VertexConsumer フォールバックパス
    // =========================================================================

    private void renderBonesConsumer(IPolyMeshBone bone, PoseStack ps, VertexConsumer buf,
                                     int light, int overlay, float r, float g, float b, float a,
                                     boolean translucentPass) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!excludedBones.isEmpty() && excludedBones.contains(bone.getName())) return;
        if (ocularBonesHidden && ocularBones.contains(bone.getName())) return;
        if (ocularRingBonesHidden && ocularRingBones.contains(bone.getName())) return;
        if (divisionBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        if (isTranslucent == translucentPass) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) mesh.compileConsumer(ps.last(), buf, actualLight, overlay, r, g, b, a);
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesConsumer(child, ps, buf, light, overlay, r, g, b, a, translucentPass);
        }

        ps.popPose();
    }

    /** 指定ボーン配下を通常描画から除外する（additional_magazine アニメーション中に使用） */
    public void setExcludeSubtree(String rootBoneName) {
        if (rootBoneName.equals(excludeSubtreeRoot)) return;
        excludeSubtreeRoot = rootBoneName;
        excludedBones.clear();
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone != null) collectSubtreeBones(bone, excludedBones);
    }

    public void addExcludeSubtree(String rootBoneName) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone != null) {
            collectSubtreeBones(bone, excludedBones);
            excludeSubtreeRoot = null;
        }
    }

    public void clearExcludeSubtree() {
        excludeSubtreeRoot = null;
        excludedBones.clear();
    }

    private void collectSubtreeBones(IPolyMeshBone bone, Set<String> result) {
        result.add(bone.getName());
        for (IPolyMeshBone child : bone.getChildren()) collectSubtreeBones(child, result);
    }

    public void renderSubtree(String rootBoneName, PoseStack ps, MultiBufferSource buf,
                              ResourceLocation tex, int light, int overlay, boolean useVBO) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        renderBonesConsumer(targetBone, ps, vc, light, overlay, 1f, 1f, 1f, 1f, false);
    }

    /**
     * additional_magazine の FunctionalRenderer から直接呼ぶ版。
     * TacZ が用意した VertexConsumer にそのまま書き込むため
     * MultiBufferSource / endBatch / turnOnLightLayer は一切不要。
     *
     * @param vertexConsumer TacZ の IFunctionalRenderer が渡す VertexConsumer
     */
    public void renderSubtreeDirect(String rootBoneName, PoseStack ps,
                                    VertexConsumer vertexConsumer, int light, int overlay) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        renderBonesConsumer(targetBone, ps, vertexConsumer, light, overlay, 1f, 1f, 1f, 1f, false);
    }

    public void renderBonesStencilOnly(String rootBoneName, PoseStack ps, MultiBufferSource buf,
                                       ResourceLocation tex, int light, int overlay) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone == null) return;
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        renderBonesConsumer(bone, ps, vc, light, overlay, 0f, 0f, 0f, 0f, false);
    }

    // =========================================================================
    // ステンシル専用ボーン（TacZ ocular 系）対応
    // =========================================================================

    /** ocular 系ボーン（とその配下）を登録する。モデル読み込み時に 1 回だけ呼ぶ想定。 */
    public void setOcularSubtrees(Collection<String> rootBoneNames) {
        collectSubtrees(rootBoneNames, ocularBones);
        specialRootBones.addAll(rootBoneNames);
    }

    /**
     * division 系ボーン（とその配下）を登録する。モデル読み込み時に 1 回だけ呼ぶ想定。
     * 登録したボーンは常に通常描画パスから除外される。
     */
    public void setDivisionSubtrees(Collection<String> rootBoneNames) {
        collectSubtrees(rootBoneNames, divisionBones);
        specialRootBones.addAll(rootBoneNames);
    }

    /** ocular_ring ボーン（とその配下）を登録する。モデル読み込み時に 1 回だけ呼ぶ想定。 */
    public void setOcularRingSubtrees(Collection<String> rootBoneNames) {
        collectSubtrees(rootBoneNames, ocularRingBones);
    }

    private void collectSubtrees(Collection<String> rootBoneNames, Set<String> result) {
        result.clear();
        for (String name : rootBoneNames) {
            IPolyMeshBone bone = findBone(this.root, name);
            if (bone != null) collectSubtreeBones(bone, result);
        }
    }

    /** true にすると、ocular 系ボーンを通常描画パスから除外する */
    public void setOcularBonesHidden(boolean hidden) {
        this.ocularBonesHidden = hidden;
    }

    /** true にすると、ocular_ring ボーンを通常描画パスから除外する */
    public void setOcularRingBonesHidden(boolean hidden) {
        this.ocularRingBonesHidden = hidden;
    }

    /**
     * TacZ の renderTempPart() から呼ばれ、指定ボーン配下のメッシュを
     * 渡された VertexConsumer に書き込む。
     *
     * <p>キューブと同じ扱いにするため、isVisible()、excludedBones、各 hidden フラグ、
     * translucent 分割はすべて無視する。面は両面で書き込む（TacZ の RenderType は
     * 裏面カリングありのため。{@link PolyMesh#compileConsumerDoubleSided} 参照）。GL のステンシル／カラーマスク／デプスマスク状態は
     * TacZ 側が設定済みのものをそのまま使う（ここでは一切触らない）。</p>
     *
     * <p>poseStack には対象ボーンの「親まで」の変換が適用済みである前提。
     * 対象ボーン自身の変換はここで適用する（BedrockPart.render() と同じ）。</p>
     *
     * <p>描画対象の配下に別の ocular / division ボーンがある場合、それは描かない
     * （キューブの場合も、それらは TacZ によって非表示にされているため描かれない）。</p>
     */
    public void renderSubtreeForStencil(String rootBoneName, PoseStack ps,
                                        VertexConsumer vc, int light, int overlay) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone == null) return;
        renderBonesForStencil(bone, ps, vc, light, overlay, true);
    }

    private void renderBonesForStencil(IPolyMeshBone bone, PoseStack ps, VertexConsumer vc,
                                       int light, int overlay, boolean isTop) {
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (!isTop && specialRootBones.contains(bone.getName())) return;

        ps.pushPose();
        bone.applyTransform(ps);

        List<PolyMesh> meshes = meshMap.get(bone.getName());
        if (meshes != null) {
            int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
            for (PolyMesh mesh : meshes) mesh.compileConsumerDoubleSided(ps.last(), vc, actualLight, overlay, 1f, 1f, 1f, 1f);
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesForStencil(child, ps, vc, light, overlay, false);
        }

        ps.popPose();
    }

    /** ボーン名でツリーを検索する */
    private IPolyMeshBone findBone(IPolyMeshBone bone, String name) {
        if (name.equals(bone.getName())) return bone;
        for (IPolyMeshBone child : bone.getChildren()) {
            IPolyMeshBone found = findBone(child, name);
            if (found != null) return found;
        }
        return null;
    }

    public boolean hasMeshInSubtree(String boneName) {
        IPolyMeshBone bone = findBone(this.root, boneName);
        if (bone == null) return false;
        return hasMeshInSubtreeInternal(bone);
    }

    private boolean hasMeshInSubtreeInternal(IPolyMeshBone bone) {
        if (meshMap.containsKey(bone.getName())) return true;
        for (IPolyMeshBone child : bone.getChildren()) {
            if (hasMeshInSubtreeInternal(child)) return true;
        }
        return false;
    }

    public void close() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.close();
        }
    }
}