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
 * 書き込まれないため、renderTempPart() の先頭に介入し、TacZ がキューブを書き込むのと
 * 同じバッファに ocular のメッシュを先に書き込んでおく（確定は TacZ に任せる）。</p>
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
     * 【TacZ Refabricated 1.2.0 対応】TacZ 1.2.0 で renderTempPart に「描画先バッファ」
     * （8 引数版はさらに「不透明度 alpha」）の引数が追加され、引数違いの版が 2 つになった。
     * 旧引数のままでは Mixin の適用に失敗してクラッシュするため、引数を合わせる。
     *
     * <p>Fabric では実行時に Minecraft のクラス名が別名（intermediary）になるため、
     * 引数の型まで書いた指定は実行時に一致しない。そこで名前だけで指定し、
     * クラス内で先に宣言されている 7 引数版（List, MultiBufferSource）に注入する。
     * 7 引数版は 8 引数版を呼ぶだけで、TML が対象にする ocular / division / ocular_ring
     * （一人称のステンシル処理）は必ずこの 7 引数版を通る。</p>
     *
     * <p>描画先バッファは TacZ が使っているものをそのままメッシュ描画にも渡す。</p>
     */
    @Inject(method = "renderTempPart", at = @At("HEAD"))
    private void meshyloader$renderOcularMesh(PoseStack poseStack, ItemDisplayContext transformType,
                                              RenderType renderType, int light, int overlay,
                                              List<BedrockPart> path, MultiBufferSource bufferSource,
                                              CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshAttachmentModel polyModel) {
            polyModel.renderStencilPartMesh(poseStack, transformType, renderType, path, light, overlay, bufferSource);
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
