package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * TacZ の {@link BedrockModel} に poly_mesh レイヤーを追加したサブクラス。
 *
 * <p>block (GunSmithTable など) の表示に使用する。
 * poly_mesh の geo.json がない場合は親クラスと全く同じ動作をする。</p>
 *
 * <h3>使用方法</h3>
 * {@link } が {@code checkModel} の末尾に介入し、
 * このクラスのインスタンスに差し替える。
 */
@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshBlockModel extends BedrockModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation texture;
    private List<IPolyMeshBone> cachedRootChildren = null;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public TaczPolyMeshBlockModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    // =========================================================================
    // render() オーバーライド
    // =========================================================================

    @Override
    public void render(PoseStack poseStack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {
        // キューブレイヤーを通常通り描画
        super.render(poseStack, transformType, renderType, light, overlay);

        // poly_mesh レイヤーを追加描画
        renderPolyMeshLayer(poseStack, transformType, light, overlay);
    }

    @Override
    public void render(PoseStack poseStack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay,
                       float red, float green, float blue, float alpha) {
        super.render(poseStack, transformType, renderType, light, overlay, red, green, blue, alpha);
        renderPolyMeshLayer(poseStack, transformType, light, overlay);
    }

    private void renderPolyMeshLayer(PoseStack poseStack, ItemDisplayContext transformType, int light, int overlay) {
        if (polyMeshModel == null || texture == null) return;

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // GUI・HEAD などのアイコン/2D表示コンテキストでは VBO を使わない。
        // VBO は OpenGL 直接描画のため、GUIのフレームバッファーに正しく書き込めず
        // アイコンが透明になる問題が発生する。
        // VBO が有効になるのは一人称・三人称・地面ドロップなど3D空間のみ。
        final boolean isIconContext = transformType == ItemDisplayContext.GUI
                || transformType == ItemDisplayContext.HEAD
                || transformType == ItemDisplayContext.FIXED
                || transformType == ItemDisplayContext.NONE;
        final boolean useVBO = !isIconContext;

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態で lightTexture の有効/無効化を毎フレーム行うと重い処理になり、
        // メニューを開いている間 FPS が大幅に低下することが分かっているため、
        // 画面が開いている間はこの操作をスキップする（VBO 自体は無効化しない）。
        final boolean isGuiLike = isIconContext || com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();

        // GUIコンテキストではlightTextureの操作をスキップする
        // (GUIレンダリング中にlightTextureを操作するとレンダリング状態が壊れる)
        if (!isGuiLike) {
            mc.gameRenderer.lightTexture().turnOnLightLayer();
        }

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
        if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(texture));
            bufferSource.endBatch(RenderType.entityCutout(texture));
        }

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
            if (!com.tacz.guns.compat.iris.IrisCompat.endBatch(bufferSource)) {
                if (net.neoforged.fml.ModList.get().isLoaded("iris")) {
                    bufferSource.endBatch(RenderType.entityTranslucentCull(texture));
                } else {
                    MeshyBatchFlushHandler.markTranslucentPending(texture);
                }
            }
        }

        if (!isGuiLike) {
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    // =========================================================================
    // loadPolyMesh
    // =========================================================================

    /**
     * geo.json を読み込んで poly_mesh ボーンを登録する。
     *
     * @param modelLocation   geo.json の ResourceLocation
     * @param textureLocation このモデルで使用するテクスチャ
     */
    public void loadPolyMesh(ResourceLocation modelLocation, ResourceLocation textureLocation) {
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
                                .map(TaczPartAdapter::new)
                                .collect(Collectors.toList());
                        return cachedRootChildren;
                    }
                };

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);
                this.texture = textureLocation;
                this.cachedRootChildren = null;

                ShaderStateTracker.register(this.polyMeshModel);

                MESH_LOG.info("[MeshyLoader] Loaded block poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyLoader] Failed to load block poly_mesh: {}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() {
        return polyMeshModel != null;
    }

    // =========================================================================
    // TaczPartAdapter
    // =========================================================================

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
                for (BedrockPart c : part.children) {
                    cachedChildren.add(new TaczPartAdapter(c));
                }
            }
            return cachedChildren;
        }

        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}