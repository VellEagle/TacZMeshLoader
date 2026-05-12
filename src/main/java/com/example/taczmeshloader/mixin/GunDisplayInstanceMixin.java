package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GunDisplayInstance.class, remap = false)
public class GunDisplayInstanceMixin {

    @Shadow
    private BedrockGunModel gunModel;

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private void meshyloader$afterCheckTextureAndModel(GunDisplay display, CallbackInfo ci) {
        // TacZがモデルインスタンス（TaczPolyMeshGunModel）を生成した直後に介入する
        if (this.gunModel instanceof TaczPolyMeshGunModel polyModel) {
            ResourceLocation modelId = display.getModelLocation();
            if (modelId != null) {
                // geo_models フォルダへのパスに変換
                ResourceLocation geoPath = ResourceLocation.fromNamespaceAndPath(
                        modelId.getNamespace(), 
                        "geo_models/" + modelId.getPath() + ".json"
                );

                // ここでメッシュをロードし、ボーン構造を完成させる！
                polyModel.loadPolyMesh(geoPath);
            }
        }
    }
}