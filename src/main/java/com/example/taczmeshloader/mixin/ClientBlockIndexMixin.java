package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshBlockModel;
import com.tacz.guns.client.model.bedrock.BedrockModel;
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

/**
 * {@link ClientBlockIndex} に Mixin し、block モデル生成後に poly_mesh を差し込む。
 *
 * <h3>geo.json パス規則</h3>
 * <pre>
 *   display JSON の "model" フィールド値 → geo_models/<path>.json
 *   例: "model": "mypack:block/gunsmith_table"
 *       → assets/mypack/geo_models/block/gunsmith_table.json
 * </pre>
 *
 * geo.json が存在しない場合は何もしない（後方互換）。
 */
@Mixin(value = ClientBlockIndex.class, remap = false)
public class ClientBlockIndexMixin {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    /**
     * {@code checkModel()} の末尾に介入し、block モデルを
     * {@link TaczPolyMeshBlockModel} に差し替える。
     */
    @Inject(method = "checkModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckModel(
            BlockDisplay display, ClientBlockIndex index, CallbackInfo ci) {

        ResourceLocation modelId = display.getModelLocation();
        if (modelId == null) return;

        // geo.json パス: geo_models/<path>.json
        ResourceLocation geoPath = new ResourceLocation(
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