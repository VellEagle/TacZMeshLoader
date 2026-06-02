package com.example.taczmeshloader.lrtactical;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import me.xjqsh.lrtactical.client.renderer.model.CustomBedrockModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LesRaisins Tactical Equipements 用の poly_mesh 対応モデルクラス。
 *
 * <p>{@link CustomBedrockModel} のサブクラスであるため、poly_mesh を使わない
 * 通常の cubeモデルでも完全に同じ動作をする。
 * {@link LrDisplayInstanceMixin} が {@code @Redirect} で
 * {@code new CustomBedrockModel(...)} をこのクラスに差し替えることで、
 * AnimationController が最初からこのインスタンスに紐づく。</p>
 */
@OnlyIn(Dist.CLIENT)
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
    public void render(PoseStack poseStack,
                       ItemDisplayContext transformType,
                       RenderType renderType,
                       int light, int overlay) {
        super.render(poseStack, transformType, renderType, light, overlay);
        renderPolyMeshLayer(poseStack, transformType, light, overlay);
    }

    private void renderPolyMeshLayer(PoseStack poseStack, ItemDisplayContext ctx,
                                     int light, int overlay) {
        if (polyMeshModel == null || texture == null) return;

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        boolean useVBO = true; // 全コンテキストでVBOを使用

        mc.gameRenderer.lightTexture().turnOnLightLayer();

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(texture));
            bufferSource.endBatch(RenderType.entityCutout(texture));
        }

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                    bufferSource.endBatch(RenderType.entityTranslucentCull(texture));
                } else {
                    MeshyBatchFlushHandler.markTranslucentPending(texture);
                }
            }
        }

        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    // -------------------------------------------------------------------------
    // poly_mesh ロード
    // -------------------------------------------------------------------------

    /**
     * geo_models/ に対応する poly_mesh JSON が存在すればロードする静的ヘルパー。
     * Mixin（= Mixinパッケージ外から呼べない制約あり）ではなく
     * このクラス自身のメソッドとして定義することで、Mixin から安全に呼べる。
     *
     * @param model         差し替え済みの LrPolyMeshModel インスタンス
     * @param modelLocation display JSON の "model" フィールド値
     * @param texture       解決済みテクスチャ ResourceLocation（textures/〜.png 形式）
     */
    public static void tryLoadPolyMesh(LrPolyMeshModel model,
                                       ResourceLocation modelLocation,
                                       ResourceLocation texture) {
        if (modelLocation == null) return;

        ResourceLocation geoPath = new ResourceLocation(
                modelLocation.getNamespace(),
                "geo_models/" + modelLocation.getPath() + ".json"
        );

        // poly_mesh JSON が存在する場合のみロード（なければ通常の cubeモデルとして動作）
        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) return;

        model.loadPolyMesh(geoPath, texture);
    }

    public void loadPolyMesh(ResourceLocation modelLocation, ResourceLocation texture) {
        this.texture = texture;
        try {
            if (this.polyMeshModel != null) this.polyMeshModel.close();

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

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);
                this.cachedRootChildren = null;
                ShaderStateTracker.register(this.polyMeshModel);
                MESH_LOG.info("[MeshyLoader] Loaded LR poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][LrPolyMeshModel] FAILED: location={}", modelLocation, e);
        }
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
