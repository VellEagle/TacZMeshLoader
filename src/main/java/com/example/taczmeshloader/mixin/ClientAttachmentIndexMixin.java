package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.tacz.guns.client.model.BedrockAttachmentModel;
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

import java.lang.reflect.Field;

@Mixin(value = ClientAttachmentIndex.class, remap = false)
public class ClientAttachmentIndexMixin {

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckTextureAndModel(AttachmentDisplay display, ClientAttachmentIndex index, CallbackInfo ci) {
        ResourceLocation modelId = display.getModel();
        if (modelId != null) {
            // geo_models フォルダへのパスに変換 (例: mypack:geo_models/attachment/my_scope.json)
            ResourceLocation geoPath = new ResourceLocation(modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");

            // リソースパック内にメッシュJSONが存在するかチェック
            var resource = Minecraft.getInstance().getResourceManager().getResource(geoPath);
            if (resource.isPresent()) {
                BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
                if (modelPOJO != null) {
                    BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO) ? BedrockVersion.LEGACY : BedrockVersion.NEW;

                    // アタッチメント用のメッシュモデルを生成
                    TaczPolyMeshAttachmentModel polyModel = new TaczPolyMeshAttachmentModel(modelPOJO, version);
                    polyModel.setIsScope(display.isScope());
                    polyModel.setIsSight(display.isSight());
                    polyModel.loadPolyMesh(geoPath);

                    // リフレクションを使って ClientAttachmentIndex の private フィールドに作成したメッシュモデルを上書きセットする
                    try {
                        Field field = ClientAttachmentIndex.class.getDeclaredField("attachmentModel");
                        field.setAccessible(true);
                        field.set(index, polyModel);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}