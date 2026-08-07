package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshAmmoModel;
import com.tacz.guns.client.model.BedrockAmmoModel;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.index.ClientAmmoIndex;
import com.tacz.guns.client.resource.pojo.display.ammo.AmmoDisplay;
import com.tacz.guns.client.resource.pojo.display.ammo.AmmoEntityDisplay;
import com.tacz.guns.client.resource.pojo.display.ammo.ShellDisplay;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(value = ClientAmmoIndex.class, remap = false)
public class ClientAmmoIndexMixin {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckTextureAndModel(
            AmmoDisplay display, ClientAmmoIndex index, CallbackInfo ci) {

        ResourceLocation modelId = display.getModelLocation();
        if (modelId == null) return;

        ResourceLocation geoPath = toGeoPath(modelId);
        if (isGeoAbsent(geoPath)) return;

        BedrockModelPOJO pojo = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (pojo == null) return;

        BedrockVersion version = resolveVersion(pojo);
        ResourceLocation texture = display.getModelTexture();

        TaczPolyMeshAmmoModel polyModel = new TaczPolyMeshAmmoModel(pojo, version);
        polyModel.loadPolyMesh(geoPath, texture);

        setField(index, ClientAmmoIndex.class, "ammoModel", polyModel);
    }

    @Inject(method = "checkAmmoEntity", at = @At("TAIL"))
    private static void meshyloader$afterCheckAmmoEntity(
            AmmoDisplay display, ClientAmmoIndex index, CallbackInfo ci) {

        AmmoEntityDisplay entityDisplay = display.getAmmoEntity();
        if (entityDisplay == null) return;

        ResourceLocation modelId = entityDisplay.getModelLocation();
        if (modelId == null) return;

        ResourceLocation geoPath = toGeoPath(modelId);
        if (isGeoAbsent(geoPath)) return;

        BedrockModelPOJO pojo = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (pojo == null) return;

        BedrockVersion version = resolveVersion(pojo);
        ResourceLocation texture = entityDisplay.getModelTexture();

        TaczPolyMeshAmmoModel polyModel = new TaczPolyMeshAmmoModel(pojo, version);
        polyModel.loadPolyMesh(geoPath, texture);

        setField(index, ClientAmmoIndex.class, "ammoEntityModel", polyModel);
    }

    @Inject(method = "checkShell", at = @At("TAIL"))
    private static void meshyloader$afterCheckShell(
            AmmoDisplay display, ClientAmmoIndex index, CallbackInfo ci) {

        ShellDisplay shellDisplay = display.getShellDisplay();
        if (shellDisplay == null) return;

        ResourceLocation modelId = shellDisplay.getModelLocation();
        if (modelId == null) return;

        ResourceLocation geoPath = toGeoPath(modelId);
        if (isGeoAbsent(geoPath)) return;

        BedrockModelPOJO pojo = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (pojo == null) return;

        BedrockVersion version = resolveVersion(pojo);
        ResourceLocation texture = shellDisplay.getModelTexture();

        TaczPolyMeshAmmoModel polyModel = new TaczPolyMeshAmmoModel(pojo, version);
        polyModel.loadPolyMesh(geoPath, texture);

        setField(index, ClientAmmoIndex.class, "shellModel", polyModel);
    }

    private static ResourceLocation toGeoPath(ResourceLocation modelId) {
        return ResourceLocation.fromNamespaceAndPath(
                modelId.getNamespace(),
                "geo_models/" + modelId.getPath() + ".json");
    }

    private static boolean isGeoAbsent(ResourceLocation geoPath) {
        return Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty();
    }

    private static BedrockVersion resolveVersion(BedrockModelPOJO pojo) {
        return BedrockVersion.isLegacyVersion(pojo) ? BedrockVersion.LEGACY : BedrockVersion.NEW;
    }

    private static void setField(Object target, Class<?> clazz, String fieldName, BedrockAmmoModel value) {
        try {
            Field field = findField(clazz, fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            LOG.error("[MeshyLoader] Failed to inject TaczPolyMeshAmmoModel into field '{}': {}",
                    fieldName, e.getMessage(), e);
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
