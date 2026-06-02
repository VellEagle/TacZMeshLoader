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
    private volatile Pair<BedrockGunModel, ResourceLocation> lodModel;

    // -----------------------------------------------------------------------
    // 通常モデル（既存）
    // -----------------------------------------------------------------------

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private void meshyloader$afterCheckTextureAndModel(GunDisplay display, CallbackInfo ci) {
        if (this.gunModel instanceof TaczPolyMeshGunModel polyModel) {
            ResourceLocation modelId = display.getModelLocation();
            if (modelId != null) {
                ResourceLocation geoPath = new ResourceLocation(
                        modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");
                polyModel.loadPolyMesh(geoPath);
            }
        }
    }

    // -----------------------------------------------------------------------
    // LODモデル（新規）
    // -----------------------------------------------------------------------

    /**
     * checkLod() の末尾に介入し、LOD用モデルにも poly_mesh を適用する。
     *
     * <p>TacZの checkLod() は {@code new BedrockGunModel(pojo, version)} を生成して
     * {@code lodModel} にセットする。ここで介入し、LOD用 geo.json が存在すれば
     * {@link TaczPolyMeshGunModel} に差し替える。</p>
     *
     * <p>LOD用 geo.json のパス規則：<br>
     * display JSON の {@code lod.model} フィールドに対し、
     * {@code geo_models/<path>.json} を探す（通常モデルと同じパス変換）。<br>
     * 例: {@code "lod": {"model": "mypack:guns/ak47"}}
     * → {@code assets/mypack/geo_models/guns/lod/ak47.json}</p>
     *
     * <p>LODモデルは通常モデルと同じ TaczPolyMeshGunModel を使用する。
     * ARCompat・VBO・OculusCompat は通常モデルと完全に同じ描画パスを経由する。</p>
     */
    @Inject(method = "checkLod", at = @At("TAIL"))
    private void meshyloader$afterCheckLod(GunDisplay display, CallbackInfo ci) {
        if (this.lodModel == null) return;

        GunLod gunLod = display.getGunLod();
        if (gunLod == null || gunLod.getModelLocation() == null) return;

        ResourceLocation lodModelId = gunLod.getModelLocation();

        // LOD用 geo.json パス: geo_models/<path>.json（通常モデルと同じルール）
        ResourceLocation geoPath = new ResourceLocation(
                lodModelId.getNamespace(),
                "geo_models/" + lodModelId.getPath() + ".json");

        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(lodModelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshGunModel polyLodModel = new TaczPolyMeshGunModel(modelPOJO, version);
        polyLodModel.loadPolyMesh(geoPath);
        // LOD専用テクスチャを固定（display.getModelTexture()ではなくlod.textureを使う）
        polyLodModel.setOverrideTexture(gunLod.getModelTexture());

        // lodModel フィールドを差し替え（テクスチャは既存のものを引き継ぐ）
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
