package com.example.taczmeshloader.mixin.tso;

import com.example.taczmeshloader.compat.tso.TsoCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.util.RenderHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * メッシュ銃 + スコープ + TaCZ: Scope Overhaul（TSO）の組み合わせ用。
 *
 * <p>TacZ の {@code BedrockGunModel#render} は「スコープ → 銃本体（スコープの覗き穴を
 * ステンシルで避ける）」の順に描き、最後にステンシルをクリアする。メッシュ銃は
 * TacZ の描画の後でメッシュを描くため、以前はスコープをもう一度描き直して
 * ステンシルを作り直していた。しかし TSO が入っていると、描き直しのたびに TSO の
 * レンズ処理（ガラス効果・レンズ位置の記録など）がもう一度走り、レンズの映像が
 * ずれて重なったり、照準が揺れ続けたりする。</p>
 *
 * <p>そこで、メッシュ銃が頼んだとき（{@link TsoCompat#shouldKeepGunStencil()}）だけ、
 * TacZ の最後の 3 つの後始末（ステンシルテストの無効化・ステンシルのクリア）を止め、
 * スコープが書き込んだステンシルを残す。後始末はメッシュを描き終えた後に
 * TaczPolyMeshGunModel 側で行う。それ以外のとき（キューブだけの銃・TSO 未導入）は
 * 何も変えない。</p>
 *
 * <p>{@code require = 0}：TacZ の更新で該当箇所が無くなってもクラッシュせず、
 * TaczPolyMeshGunModel は従来通り「スコープの描き直し」に戻る。</p>
 */
@Mixin(value = BedrockGunModel.class, remap = false)
public class BedrockGunModelStencilMixin {

    // 【TacZ 1.2.0 対応】ステンシルの後始末は、全ての render が集約される
    // 11 引数版（色・不透明度・描画先バッファ付き）に移動したため、そちらを狙う。
    private static final String RENDER =
            "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;II"
                    + "FFFFLnet/minecraft/client/renderer/MultiBufferSource;)V";

    @Redirect(method = RENDER,
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/util/RenderHelper;disableItemEntityStencilTest()V"),
            require = 0, remap = false)
    private void taczmeshloader$keepStencilTest() {
        if (TsoCompat.shouldKeepGunStencil()) {
            TsoCompat.markGunStencilClearSkipped();
            return;
        }
        RenderHelper.disableItemEntityStencilTest();
    }

    @Redirect(method = RENDER,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clearStencil(I)V"),
            require = 0, remap = false)
    private void taczmeshloader$keepStencilClearValue(int value) {
        if (TsoCompat.shouldKeepGunStencil()) return;
        RenderSystem.clearStencil(value);
    }

    @Redirect(method = RENDER,
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),
            require = 0, remap = false)
    private void taczmeshloader$keepStencilBuffer(int mask, boolean checkError) {
        if (TsoCompat.shouldKeepGunStencil()) return;
        RenderSystem.clear(mask, checkError);
    }
}
