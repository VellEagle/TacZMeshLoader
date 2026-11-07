package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.client.resource.pojo.display.gun.GunLod;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.ClientAssetsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = GunDisplayInstance.class, remap = false)
public class GunDisplayInstanceMixin {

    @Shadow
    private BedrockGunModel gunModel;

    @Shadow
    private Pair<BedrockGunModel, ResourceLocation> lodModel; // TacZ 1.2.0 で volatile が外れたため合わせた

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private void meshyloader$afterCheckTextureAndModel(GunDisplay display, CallbackInfo ci) {
        ResourceLocation modelId = display.getModelLocation();
        if (modelId == null) return;
        ResourceLocation geoPath = ResourceLocation.fromNamespaceAndPath(
                modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");

        // 1) 従来どおり "model_type": "mesh" が指定された銃（TacZ が TML 用のモデルで作成済み）
        if (this.gunModel instanceof TaczPolyMeshGunModel polyModel) {
            polyModel.loadPolyMesh(geoPath);
            return;
        }

        // 2) 【自動判定】"model_type" を書かなくても、モデルファイルにメッシュ（poly_mesh）があれば
        //    TML 用のモデルに差し替える。
        //    ・TacZ 標準の型（BedrockGunModel そのもの）の銃だけが対象。他のアドオンが登録した
        //      独自の model_type の銃は、そのアドオンの機能を壊さないよう差し替えない。
        //    ・メッシュの無い銃は、モデルを作り直さずに TacZ のまま使う（読み込み負荷も増えない）。
        //    ・この直後に TacZ がアニメーションを組み立てるため、差し替えたモデルで正しく動く。
        if (this.gunModel == null || this.gunModel.getClass() != BedrockGunModel.class) return;
        if (!com.example.taczmeshloader.tacz.GeoMeshProbe.containsPolyMesh(geoPath)) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (modelPOJO == null) return;
        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshGunModel polyModel = new TaczPolyMeshGunModel(modelPOJO, version);
        polyModel.loadPolyMesh(geoPath);
        if (!polyModel.hasMeshData()) return;
        this.gunModel = polyModel;
    }

    @Inject(method = "checkLod", at = @At("TAIL"))
    private void meshyloader$afterCheckLod(GunDisplay display, CallbackInfo ci) {
        if (this.lodModel == null) return;

        GunLod gunLod = display.getGunLod();
        if (gunLod == null || gunLod.getModelLocation() == null) return;

        ResourceLocation lodModelId = gunLod.getModelLocation();

        ResourceLocation geoPath = ResourceLocation.fromNamespaceAndPath(
                lodModelId.getNamespace(),
                "geo_models/" + lodModelId.getPath() + ".json");

        if (!com.example.taczmeshloader.tacz.GeoMeshProbe.containsPolyMesh(geoPath)) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(lodModelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshGunModel polyLodModel = new TaczPolyMeshGunModel(modelPOJO, version);
        polyLodModel.loadPolyMesh(geoPath);
        // メッシュが無い LOD モデルは作り直さず、TacZ のまま使う
        if (!polyLodModel.hasMeshData()) return;

        try {
            Field field = GunDisplayInstance.class.getDeclaredField("lodModel");
            field.setAccessible(true);
            field.set(this, Pair.of(polyLodModel, this.lodModel.getRight()));
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader")
                    .error("[MeshyLoader] Failed to inject LOD PolyMesh for gun: {}", lodModelId, e);
        }
    }
}
