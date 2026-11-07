package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentLod;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = ClientAttachmentIndex.class, remap = false)
public class ClientAttachmentIndexMixin {

    // -----------------------------------------------------------------------
    // 通常モデル（既存）
    // -----------------------------------------------------------------------

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckTextureAndModel(
            AttachmentDisplay display, ClientAttachmentIndex index, CallbackInfo ci) {

        ResourceLocation modelId = display.getModel();
        if (modelId == null) return;

        ResourceLocation geoPath = new ResourceLocation(
                modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");

        if (!com.example.taczmeshloader.tacz.GeoMeshProbe.containsPolyMesh(geoPath)) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshAttachmentModel polyModel = new TaczPolyMeshAttachmentModel(modelPOJO, version);
        polyModel.setIsScope(display.isScope());
        polyModel.setIsSight(display.isSight());
        polyModel.loadPolyMesh(geoPath);

        // 【TacZ 1.2.0 対応】TacZ のモデルファイルは geo_models/ にあるため、上の存在チェックは
        // キューブだけのアタッチメント（TacZ 標準のスコープ等）でも通ってしまい、以前は全アタッチメントを
        // TML のモデルに作り直していた。TacZ 1.2.0 ではモデルに「レンズ設定」（暗視・サーマル等）が
        // 追加されたが、作り直したモデルにはそれが渡らず、TML を入れるだけで全スコープの
        // 暗視・サーマルが効かなくなっていた。
        // → メッシュが無ければ作り直さず、TacZ が作ったモデルをそのまま使う。
        if (!polyModel.hasMeshData()) return;
        // メッシュがある場合も、TacZ と同じくレンズ設定を渡す（TacZ の checkTextureAndModel と同じ値）
        polyModel.setScopeLens(display.getScopeLens());

        try {
            Field field = ClientAttachmentIndex.class.getDeclaredField("attachmentModel");
            field.setAccessible(true);
            field.set(index, polyModel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // LODモデル（新規）
    // -----------------------------------------------------------------------

    /**
     * checkLod() の末尾に介入し、アタッチメントのLODモデルにも poly_mesh を適用する。
     *
     * <p>LOD用 geo.json のパス規則：<br>
     * display JSON の {@code lod.model} に対し {@code geo_models/<path>.json} を探す（通常モデルと同じパス変換）。<br>
     * 例: {@code "lod": {"model": "mypack:attachment/scope"}}
     * → {@code assets/mypack/geo_models/attachment/lod/scope.json}</p>
     */
    @Inject(method = "checkLod", at = @At("TAIL"))
    private static void meshyloader$afterCheckLod(
            AttachmentDisplay display, ClientAttachmentIndex index, CallbackInfo ci) {

        // lodModel が生成されていなければスキップ
        Pair<BedrockAttachmentModel, ResourceLocation> currentLod;
        try {
            Field lodField = ClientAttachmentIndex.class.getDeclaredField("lodModel");
            lodField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Pair<BedrockAttachmentModel, ResourceLocation> lod =
                    (Pair<BedrockAttachmentModel, ResourceLocation>) lodField.get(index);
            currentLod = lod;
        } catch (Exception e) {
            return;
        }
        if (currentLod == null) return;

        AttachmentLod attachmentLod = display.getAttachmentLod();
        if (attachmentLod == null || attachmentLod.getModelLocation() == null) return;

        ResourceLocation lodModelId = attachmentLod.getModelLocation();

        // LOD用 geo.json: geo_models/<path>.json（通常モデルと同じルール）
        ResourceLocation geoPath = new ResourceLocation(
                lodModelId.getNamespace(),
                "geo_models/" + lodModelId.getPath() + ".json");

        if (!com.example.taczmeshloader.tacz.GeoMeshProbe.containsPolyMesh(geoPath)) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(lodModelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshAttachmentModel polyLodModel = new TaczPolyMeshAttachmentModel(modelPOJO, version);
        polyLodModel.setIsScope(display.isScope());
        polyLodModel.setIsSight(display.isSight());
        polyLodModel.loadPolyMesh(geoPath);

        // 【TacZ 1.2.0 対応】メッシュが無い LOD モデルは作り直さない（通常モデルと同じ理由）
        if (!polyLodModel.hasMeshData()) return;

        try {
            Field lodField = ClientAttachmentIndex.class.getDeclaredField("lodModel");
            lodField.setAccessible(true);
            lodField.set(index, Pair.of(polyLodModel, currentLod.getRight()));
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader")
                    .error("[MeshyLoader] Failed to inject LOD PolyMesh for attachment: {}", lodModelId, e);
        }
    }
}
