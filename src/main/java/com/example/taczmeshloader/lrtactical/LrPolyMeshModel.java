package com.example.taczmeshloader.lrtactical;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.render.MeshyBuffers;
import com.example.taczmeshloader.render.ScreenRenderTracker;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.example.taczmeshloader.tacz.GeoMeshProbe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import me.xjqsh.lrtactical.client.renderer.model.CustomBedrockModel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LesRaisins Tactical (LR Tactical) 用の poly_mesh 対応モデルクラス（Fabric 1.21.1 版）。
 *
 * <p>対応先：非公式 Fabric 移植版「[UNOFFICIAL] LRTactical Fabric」
 * （Modrinth: lrtactical-fabric / https://github.com/khangiosipa-glitch/LRTactical-Fabric-1.21.1）。
 * 移植版の CustomBedrockModel / 各 DisplayInstance の構造は NeoForge 版と同じ。</p>
 *
 * <p>{@link CustomBedrockModel} のサブクラスなので、poly_mesh を使わない
 * 普通のキューブモデルでも元と全く同じ動作をする。
 * {@code LrDisplayInstanceMixin} が {@code new CustomBedrockModel(...)} を
 * このクラスに差し替えることで、アニメーションも最初からこのインスタンスに紐づく。</p>
 *
 * <h3>TacZ Refabricated 1.2.0 対応</h3>
 * LR は 5 引数版の render を呼ぶが、TacZ 1.2.0 では全ての render が
 * 描画先バッファ付きの 10 引数版に集約されるため、10 引数版だけを上書きし、
 * TacZ と同じ描画先バッファにメッシュを描く（弾薬・ブロックモデルと同じ方式）。
 */
@Environment(EnvType.CLIENT)
public class LrPolyMeshModel extends CustomBedrockModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation texture;
    private List<IPolyMeshBone> cachedRootChildren = null;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public LrPolyMeshModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    // -------------------------------------------------------------------------
    // レンダリング
    // -------------------------------------------------------------------------

    @Override
    public void render(PoseStack poseStack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay,
                       float red, float green, float blue, float alpha,
                       MultiBufferSource bufferSource) {
        // キューブ部分は通常通り描画
        super.render(poseStack, transformType, renderType, light, overlay, red, green, blue, alpha, bufferSource);
        // メッシュ部分を追加描画（TacZ と同じ描画先バッファを使う）
        renderPolyMeshLayer(poseStack, transformType, light, overlay, bufferSource);
    }

    private void renderPolyMeshLayer(PoseStack poseStack, ItemDisplayContext ctx,
                                     int light, int overlay, MultiBufferSource bufferSource) {
        if (polyMeshModel == null || texture == null) return;

        Minecraft mc = Minecraft.getInstance();

        // GUI・HEAD などのアイコン表示では VBO 直接描画だとアイコンが透明になるため VBO を使わない。
        final boolean isIconContext = ctx == ItemDisplayContext.GUI
                || ctx == ItemDisplayContext.HEAD
                || ctx == ItemDisplayContext.FIXED
                || ctx == ItemDisplayContext.NONE;
        final boolean useVBO = !isIconContext;

        // 画面（インベントリ等）を開いている間は lightTexture の切り替えを省略する（FPS 低下対策）。
        final boolean isGuiLike = isIconContext || ScreenRenderTracker.isRenderingScreen();

        if (!isGuiLike) {
            mc.gameRenderer.lightTexture().turnOnLightLayer();
        }

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
        MeshyBuffers.flushCutout(bufferSource, texture);

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
            MeshyBuffers.flushTranslucent(bufferSource, texture);
        }

        if (!isGuiLike) {
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    // -------------------------------------------------------------------------
    // poly_mesh ロード
    // -------------------------------------------------------------------------

    /**
     * モデルファイル（geo_models/〜.json）に poly_mesh が書かれていればロードする。
     * 書かれていなければ何もしない（普通のキューブモデルとして動作）。
     *
     * @param model         差し替え済みの LrPolyMeshModel
     * @param modelLocation display JSON の "model" の値
     * @param texture       解決済みテクスチャ
     */
    public static void tryLoadPolyMesh(LrPolyMeshModel model,
                                       ResourceLocation modelLocation,
                                       ResourceLocation texture) {
        if (model == null || modelLocation == null) return;

        ResourceLocation geoPath = ResourceLocation.fromNamespaceAndPath(
                modelLocation.getNamespace(),
                "geo_models/" + modelLocation.getPath() + ".json"
        );

        // ファイルがあっても poly_mesh が無ければ読み込まない
        if (!GeoMeshProbe.containsPolyMesh(geoPath)) return;

        model.loadPolyMesh(geoPath, texture);
    }

    public void loadPolyMesh(ResourceLocation modelLocation, ResourceLocation texture) {
        this.texture = texture;
        try {
            if (this.polyMeshModel != null) {
                ShaderStateTracker.unregisterModel(this.polyMeshModel);
                this.polyMeshModel.close();
                this.polyMeshModel = null;
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
                                .map(LrPartAdapter::new).collect(Collectors.toList());
                        return cachedRootChildren;
                    }
                };

                PolyMeshModel loaded = new PolyMeshModel(adaptedRoot, rawJson);
                this.cachedRootChildren = null;
                if (!loaded.hasAnyMesh()) {
                    loaded.close();
                    return;
                }
                this.polyMeshModel = loaded;
                ShaderStateTracker.registerModel(this.polyMeshModel);
                MESH_LOG.info("[MeshyLoader] Loaded LR poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyLoader][LrPolyMeshModel] Failed to load LR poly_mesh: {}", modelLocation, e);
        }
    }

    public boolean hasMeshData() {
        return polyMeshModel != null && polyMeshModel.hasAnyMesh();
    }

    // -------------------------------------------------------------------------
    // BedrockPart → IPolyMeshBone アダプター
    // -------------------------------------------------------------------------

    private static class LrPartAdapter implements IPolyMeshBone {
        private final BedrockPart part;
        private List<IPolyMeshBone> cachedChildren;
        LrPartAdapter(BedrockPart part) { this.part = part; }
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
                for (BedrockPart c : part.children) cachedChildren.add(new LrPartAdapter(c));
            return cachedChildren;
        }
        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}
