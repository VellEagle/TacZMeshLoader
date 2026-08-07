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

/**
 * {@link ClientAmmoIndex} に Mixin し、ammo / ammo_entity / shell の
 * 各モデル生成後に poly_mesh を差し込む。
 *
 * <h3>geo.json パス規則（全種共通）</h3>
 * <pre>
 *   display JSON の "model" フィールド値 → geo_models/<path>.json
 *   例: "model": "mypack:ammo/bullet"
 *       → assets/mypack/geo_models/ammo/bullet.json
 * </pre>
 *
 * geo.json が存在しない場合は何もしない（後方互換）。
 */
@Mixin(value = ClientAmmoIndex.class, remap = false)
public class ClientAmmoIndexMixin {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    // =========================================================================
    // ammo (アイテム表示モデル)
    // =========================================================================

    /**
     * {@code checkTextureAndModel()} の末尾に介入し、ammo アイテムモデルを
     * {@link TaczPolyMeshAmmoModel} に差し替える。
     */
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

    // =========================================================================
    // ammo_entity (弾丸エンティティモデル)
    // =========================================================================

    /**
     * {@code checkAmmoEntity()} の末尾に介入し、ammo_entity モデルを
     * {@link TaczPolyMeshAmmoModel} に差し替える。
     */
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

    // =========================================================================
    // shell (薬莢モデル)
    // =========================================================================

    /**
     * {@code checkShell()} の末尾に介入し、shell モデルを
     * {@link TaczPolyMeshAmmoModel} に差し替える。
     */
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

    // =========================================================================
    // 共通ユーティリティ
    // =========================================================================

    /**
     * display JSON の model ID から geo.json パスへ変換する。
     * 規則: geo_models/<path>.json（gun/attachment と同じ）
     */
    private static ResourceLocation toGeoPath(ResourceLocation modelId) {
        return ResourceLocation.fromNamespaceAndPath(
                modelId.getNamespace(),
                "geo_models/" + modelId.getPath() + ".json");
    }

    /** geo.json が存在しない場合 true */
    private static boolean isGeoAbsent(ResourceLocation geoPath) {
        return Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty();
    }

    /** POJO からバージョンを判定 */
    private static BedrockVersion resolveVersion(BedrockModelPOJO pojo) {
        return BedrockVersion.isLegacyVersion(pojo) ? BedrockVersion.LEGACY : BedrockVersion.NEW;
    }

    /** リフレクションで private フィールドを書き換える */
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