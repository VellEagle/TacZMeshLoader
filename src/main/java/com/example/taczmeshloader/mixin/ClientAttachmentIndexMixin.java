package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.core.AttachmentIndexGuard;
import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientAttachmentIndex.class, remap = false)
public class ClientAttachmentIndexMixin {

    // ResourceLocation(String, String) is deprecated for removal in 1.21+; no alternative exists in 1.20.1.
    @SuppressWarnings("removal")
    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckTextureAndModel(
            AttachmentDisplay display, ClientAttachmentIndex index, CallbackInfo ci) {

        ResourceLocation modelId = display.getModel();
        if (modelId == null) return;

        ResourceLocation geoPath = new ResourceLocation(
                modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");

        // Only proceed if a poly_mesh JSON exists for this attachment.
        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) return;

        // --- Tier 1: expensive loadPolyMesh only once ---
        // Check if we already have a cached model for this index (and same path).
        TaczPolyMeshAttachmentModel polyModel = AttachmentIndexGuard.MODEL_CACHE.get(index);
        if (polyModel == null || !geoPath.equals(polyModel.getLoadedPolyMeshPath())) {
            BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
            if (modelPOJO == null) return;

            BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                    ? BedrockVersion.LEGACY : BedrockVersion.NEW;

            polyModel = new TaczPolyMeshAttachmentModel(modelPOJO, version);
            polyModel.setIsScope(display.isScope());
            polyModel.setIsSight(display.isSight());
            polyModel.loadPolyMesh(geoPath);

            AttachmentIndexGuard.MODEL_CACHE.put(index, polyModel);
        }

        // --- Tier 2: always re-assign the model ---
        // TacZ may reset attachmentModel between render frames, so we re-set it every call.
        ((ClientAttachmentIndexAccessor)(Object)index).setAttachmentModel(polyModel);
    }
}