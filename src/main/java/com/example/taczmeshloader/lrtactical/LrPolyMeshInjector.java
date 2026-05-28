package com.example.taczmeshloader.lrtactical;

import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Field;

/**
 * LrDisplayInstanceMixin から呼ばれる静的ヘルパークラス。
 *
 * <p>Mixinクラス自身（com.example.taczmeshloader.mixin.* 配下）は
 * 他クラスから直接参照できないというMixinの制約があるため、
 * ヘルパーロジックをMixinパッケージの外であるこのクラスに分離している。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class LrPolyMeshInjector {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    private LrPolyMeshInjector() {}

    /**
     * DisplayInstance の model フィールドを LrPolyMeshModel に差し替える。
     *
     * <p>geo_models/ 以下に対応する geo.json が存在しない場合は何もしない（後方互換）。</p>
     *
     * @param modelLocation  display JSON の "model" フィールド値
     * @param rawTexLocation display JSON の "texture" フィールド値（textures/ プレフィックスなし）
     * @param displayInstance 上書き対象の DisplayInstance
     * @param displayClass    リフレクション用クラス参照
     */
    public static void tryInject(
            ResourceLocation modelLocation,
            ResourceLocation rawTexLocation,
            Object displayInstance,
            Class<?> displayClass) {

        if (modelLocation == null) return;

        // display JSON の "model": "mypack:melee/knife"
        // → geo_models/melee/knife.json を探す
        ResourceLocation geoPath = new ResourceLocation(
                modelLocation.getNamespace(),
                "geo_models/" + modelLocation.getPath() + ".json"
        );

        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) {
            return; // geo.json がなければ通常の CustomBedrockModel をそのまま使う
        }

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelLocation);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        // テクスチャパスを textures/〜.png 形式に変換（LesRaisins の create() と同じ変換）
        ResourceLocation texture = rawTexLocation == null ? null
                : new ResourceLocation(rawTexLocation.getNamespace(),
                                       "textures/" + rawTexLocation.getPath() + ".png");

        LrPolyMeshModel polyModel = new LrPolyMeshModel(modelPOJO, version);
        polyModel.loadPolyMesh(geoPath, texture);

        // リフレクションで DisplayInstance の private model フィールドを上書き
        try {
            Field field = findField(displayClass, "model");
            field.setAccessible(true);
            field.set(displayInstance, polyModel);
        } catch (Exception e) {
            LOG.error("[MeshyLoader] Failed to inject LrPolyMeshModel into {}",
                    displayClass.getSimpleName(), e);
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
