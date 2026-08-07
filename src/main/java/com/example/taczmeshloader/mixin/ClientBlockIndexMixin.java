package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshBlockModel;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import com.tacz.guns.client.resource.pojo.display.block.BlockDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = ClientBlockIndex.class, remap = false)
public class ClientBlockIndexMixin {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    @Inject(method = "checkModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckModel(
            BlockDisplay display, ClientBlockIndex index, CallbackInfo ci) {

        ResourceLocation modelId = display.getModelLocation();
        if (modelId == null) return;

        ResourceLocation geoPath = ResourceLocation.fromNamespaceAndPath(
                modelId.getNamespace(),
                "geo_models/" + modelId.getPath() + ".json");

        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) return;

        BedrockModelPOJO pojo = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (pojo == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(pojo)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        ResourceLocation texture = display.getModelTexture();

        TaczPolyMeshBlockModel polyModel = new TaczPolyMeshBlockModel(pojo, version);
        polyModel.loadPolyMesh(geoPath, texture);

        try {
            Field field = findField(ClientBlockIndex.class, "model");
            field.setAccessible(true);
            field.set(index, polyModel);
        } catch (Exception e) {
            LOG.error("[MeshyLoader] Failed to inject TaczPolyMeshBlockModel: {}", modelId, e);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name + " not found in " + clazz.getName());
    }
}
