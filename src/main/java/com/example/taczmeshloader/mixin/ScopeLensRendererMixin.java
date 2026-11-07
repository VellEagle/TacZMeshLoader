package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.tacz.guns.client.renderer.scope.ScopeLensRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【TacZ 1.2.0 新スコープレンズ対応】TML のメッシュ銃がステンシルを作り直すためにスコープを
 * 描き直している間だけ、TacZ のレンズ記録（ScopeLensRenderer.capture）を止める。
 *
 * <p>TacZ 1.2.0 はスコープを描くたびにレンズを記録し、フレームの最後にレンズ効果
 * （歪み・色収差・縁の暗さ・暗視・サーマル）をかける。描き直しでも記録されると
 * レンズ効果が 2 回重ねがけされてしまう。</p>
 *
 * <p>true（記録済み）を返すと、TacZ はこの回のレティクル描画を省略する。
 * 1 回目の描画で記録したレンズとレティクルはそのまま使われるので、表示は欠けない。</p>
 *
 * <p>capture は同名の別版が無いため名前だけで指定する（全ローダー共通）。
 * require = 0：TacZ の今後の更新でこの処理が無くなっても、クラッシュはさせない。</p>
 */
@Mixin(value = ScopeLensRenderer.class, remap = false)
public class ScopeLensRendererMixin {

    @Inject(method = "capture", at = @At("HEAD"), cancellable = true, require = 0)
    private static void taczmeshloader$skipDuplicateCapture(CallbackInfoReturnable<Boolean> cir) {
        if (TaczPolyMeshGunModel.isReRenderingScope()) {
            cir.setReturnValue(true);
        }
    }
}
