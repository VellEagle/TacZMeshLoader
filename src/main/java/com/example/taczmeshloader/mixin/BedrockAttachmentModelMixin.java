package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * TacZ の ocular / ocular_ring / division（スコープ処理用ボーン）をメッシュモデルでも動作させるための Mixin。
 *
 * <p>TacZ は ocular を private メソッド renderTempPart() で描画するが、これは
 * BedrockPart のキューブしか描かない。メッシュのみの ocular だとステンシルに何も
 * 書き込まれないため、renderTempPart() の先頭に介入し、同じ GL 状態のまま
 * ocular のメッシュも描画させる。</p>
 *
 * <p>一人称スコープ時の ocular_ring も同様に、TacZ の描画タイミング
 * （ocular のステンシル書き込みより前）でメッシュを描画させる。</p>
 *
 * <p>division（レティクル）も同様に、TacZ が指定したステンシル条件のまま
 * メッシュを描画させる。</p>
 *
 * <p>TaczPolyMeshAttachmentModel 以外（通常の TacZ アタッチメント）や、
 * それ以外のパーツ（scope_body）では何もしない。</p>
 */
@Mixin(value = BedrockAttachmentModel.class, remap = false)
public class BedrockAttachmentModelMixin {

    @Inject(method = "renderTempPart", at = @At("HEAD"))
    private void meshyloader$renderOcularMesh(PoseStack poseStack, ItemDisplayContext transformType,
                                              RenderType renderType, int light, int overlay,
                                              List<BedrockPart> path, CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshAttachmentModel polyModel) {
            polyModel.renderStencilPartMesh(poseStack, transformType, path, light, overlay);
        }
    }
}
