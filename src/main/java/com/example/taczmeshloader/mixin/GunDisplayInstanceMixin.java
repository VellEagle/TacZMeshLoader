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

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private void meshyloader$afterCheckTextureAndModel(GunDisplay display, CallbackInfo ci) {
        if (this.gunModel instanceof TaczPolyMeshGunModel polyModel) {
            ResourceLocation modelId = display.getModelLocation();
            if (modelId != null) {
                ResourceLocation geoPath = ResourceLocation.fromNamespaceAndPath(
                        modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");
                polyModel.loadPolyMesh(geoPath);
            }
        }
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

        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(lodModelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshGunModel polyLodModel = new TaczPolyMeshGunModel(modelPOJO, version);
        polyLodModel.loadPolyMesh(geoPath);

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
