package com.example.taczmeshloader.mixin.tso;

import com.example.taczmeshloader.compat.tso.TsoCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TaCZ: Scope Overhaul（TSO）の PipLensRenderer への介入。
 *
 * <h3>1. 遅延描画でメッシュが消える問題</h3>
 * <p>シェーダー使用時、TSO は ocular / division を後から描き直すための Runnable を
 * {@code makeDeferredMeshWriter} で作る。この Runnable は BedrockPart をキューブだけで
 * 描くため、メッシュのパーツが消えてしまう。そこで返り値の Runnable を包み、実行中だけ
 * {@link TsoCompat#isInDeferredWriter()} を true にする。その間だけ、パーツに追加した
 * 見えないキューブ（{@link com.example.taczmeshloader.compat.tso.TsoMeshBoundsCube}）が
 * メッシュを描く。</p>
 *
 * <h3>2. レンズの形の計算がずれる問題</h3>
 * <p>TSO は {@code computeOcularGeometry} で、ocular ボーン直下のキューブの X / Y の幅と
 * ボーンのピボット位置からレンズの大きさと中心を求める。ocular（またはその親）が回転している
 * モデルや、回転付きキューブで作られた ocular では計算が大きくずれ、覗き穴が欠けたり
 * 照準（銃の引き寄せ量）が揺れ続けたりする。メッシュローダーで読み込んだスコープについては、
 * 計算結果を向きに依存しない方法で作り直す。</p>
 *
 * <p>{@code @Pseudo} なので TSO が入っていなければこの Mixin は無視される。
 * TSO 側の更新でメソッドが無くなった場合も、{@code require = 0} によりクラッシュせず、
 * その補正だけが効かなくなる。</p>
 */
@Pseudo
@Mixin(targets = "com.raiiiden.taczso.PipLensRenderer", remap = false)
public class TsoPipLensRendererMixin {

    @Inject(method = "makeDeferredMeshWriter", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void taczmeshloader$wrapDeferredWriter(CallbackInfoReturnable<Runnable> cir) {
        Runnable original = cir.getReturnValue();
        if (original != null) {
            cir.setReturnValue(TsoCompat.wrapDeferredWriter(original));
        }
    }

    /**
     * TSO が計算したレンズの形（中心・半径・画面上の位置と大きさ）を、メッシュローダーで
     * 読み込んだスコープについては、向きに依存しない計算で作り直す
     * （{@link TsoCompat#correctOcularGeometry} 参照）。
     */
    @Inject(method = "computeOcularGeometry", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void taczmeshloader$correctOcularGeometry(PoseStack poseStack, List<BedrockPart> path,
                                                            float scopeViewRadiusModifier,
                                                            CallbackInfoReturnable<Object> cir) {
        Object original = cir.getReturnValue();
        Object corrected = TsoCompat.correctOcularGeometry(original, poseStack, path);
        if (corrected != original) {
            cir.setReturnValue(corrected);
        }
    }
}
