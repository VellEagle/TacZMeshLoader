package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
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
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        com.tacz.guns.util.RenderHelper.enableItemEntityStencilTest();
        RenderSystem.stencilFunc(GL11.GL_EQUAL, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
        bufferSource.endBatch(RenderType.entityCutout(tex));

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            bufferSource.endBatch(RenderType.entityTranslucentCull(tex));
        }

        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        com.tacz.guns.util.RenderHelper.disableItemEntityStencilTest();
    }

    /**
     * 非 scope/sight 時（通常描画）の PolyMesh 描画。
     */

    private void renderPolyMeshNormalAttachment(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        }
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

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態では VBO 直接描画が正しく表示されないことが実機で確認されているため、
        // その場合は VBO を無効化する。
        final boolean isGuiLike = com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();
        final boolean useVBO = !isGuiLike;
        final boolean isFirstPersonScopeOrSight = transformType.firstPerson() && isScope();

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

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
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            restorePartVisibilityForPolyMesh();

            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOnLightLayer();
            }

            if (isFirstPersonScopeOrSight) {
                renderPolyMeshThroughStencilHole(poseStack, bufferSource, cachedTexture, light, overlay, useVBO);
            } else {
                renderPolyMeshNormalAttachment(poseStack, bufferSource, cachedTexture, light, overlay, useVBO);
            }
            boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();

            if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(cachedTexture));
                bufferSource.endBatch(RenderType.entityCutout(cachedTexture));
                if (hasTrans) {
                    if (net.neoforged.fml.ModList.get().isLoaded("iris")) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(cachedTexture));
                    } else {
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(cachedTexture);
                    }
                }
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

                MESH_LOG.info("[MeshyLoader] Loaded attachment poly_mesh from: {} (ocularPolyMeshBones={})",
                        modelLocation, ocularPolyMeshBoneNames);
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
