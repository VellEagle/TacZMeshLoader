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

    /**
     * After TacZ has loaded the gun model, if it is a {@link TaczPolyMeshGunModel}
     * we immediately load the corresponding poly_mesh geometry.
     */
    // ResourceLocation(String, String) is deprecated for removal in 1.21+; no alternative exists in 1.20.1.
    @SuppressWarnings("removal")
    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private void meshyloader$afterCheckTextureAndModel(GunDisplay display, CallbackInfo ci) {
        if (!(this.gunModel instanceof TaczPolyMeshGunModel polyModel)) return;

        ResourceLocation modelId = display.getModelLocation();
        if (modelId == null) return;

        ResourceLocation geoPath = new ResourceLocation(
                modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");

        // Skip reload if we already loaded this exact path (avoids redundant work on repeated calls).
        if (geoPath.equals(polyModel.getLoadedPolyMeshPath())) return;
        polyModel.loadPolyMesh(geoPath);
    }
}