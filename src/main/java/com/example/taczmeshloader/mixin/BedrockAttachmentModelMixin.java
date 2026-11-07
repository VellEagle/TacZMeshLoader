package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.MultiBufferSource;
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

    /**
     * 【TacZ 1.2.0 対応】TacZ 1.2.0 で renderTempPart に「不透明度 alpha」と「描画先バッファ」の
     * 引数が追加され、引数違いの版が 2 つになった（7 引数版は 8 引数版を呼ぶだけ）。
     * 以前の指定（名前だけ・旧引数）のままでは Mixin の適用に失敗してクラッシュするため、
     * 実際の描画を行う 8 引数版を引数の型まで明示して狙う。
     * 描画先バッファは TacZ が使っているものをそのままメッシュ描画にも渡す。
     */
    @Inject(method = "renderTempPart(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lnet/minecraft/client/renderer/RenderType;IILjava/util/List;FLnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"))
    private void meshyloader$renderOcularMesh(PoseStack poseStack, ItemDisplayContext transformType,
                                              RenderType renderType, int light, int overlay,
                                              List<BedrockPart> path, float alpha, MultiBufferSource bufferSource,
                                              CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshAttachmentModel polyModel) {
            polyModel.renderStencilPartMesh(poseStack, transformType, path, light, overlay, bufferSource);
        }
    }

    /**
     * 【TacZ 1.2.0 新スコープレンズ対応】TacZ がレンズの範囲を計算する処理（captureLensBounds）の
     * 先頭に割り込み、メッシュの接眼部の頂点も範囲の計算に加える。
     * 詳細は {@link TaczPolyMeshAttachmentModel#captureLensMeshBounds} を参照。
     *
     * <p>captureLensBounds は同名の別版が無いため名前だけで指定する（全ローダー共通）。
     * require = 0：TacZ の今後の更新でこの処理が無くなっても、クラッシュはさせない。</p>
     */
    @Inject(method = "captureLensBounds", at = @At("HEAD"), require = 0)
    private void meshyloader$captureLensMeshBounds(PoseStack poseStack, List<BedrockPart> path,
                                                   VertexConsumer bounds, CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshAttachmentModel polyModel) {
            polyModel.captureLensMeshBounds(poseStack, path, bounds);
        }
    }
}
