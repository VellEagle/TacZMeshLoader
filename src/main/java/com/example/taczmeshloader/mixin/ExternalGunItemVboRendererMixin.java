package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.MeshPresence;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.renderer.item.ExternalGunItemVboRenderer;
import com.tacz.guns.client.renderer.item.StaticGunRenderResult;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【TacZ Refabricated 1.2.0 対応】三人称で持った銃・地面に落ちた銃・額縁の銃の
 * 「VBO 焼き込み描画」を、メッシュを使う銃に限って断る（「対応外」を返す）。
 *
 * <p>TacZ は「対応外」を受け取ると従来の描画経路（BedrockGunModel.render）に戻すため、
 * TML がメッシュを描けるようになる。詳細は {@link MeshPresence} を参照。</p>
 *
 * <p>require = 0：TacZ の今後の更新でこの処理が無くなっても、クラッシュはさせない。</p>
 */
@Mixin(value = ExternalGunItemVboRenderer.class, remap = false)
public class ExternalGunItemVboRendererMixin {

    @Inject(method = "tryRender", at = @At("HEAD"), cancellable = true, require = 0)
    private static void taczmeshloader$skipMeshGuns(ItemStack stack, ItemDisplayContext context, boolean useLod,
                                                    PoseStack pose, MultiBufferSource buffers,
                                                    int packedLight, int packedOverlay,
                                                    CallbackInfoReturnable<StaticGunRenderResult> cir) {
        if (MeshPresence.gunUsesMesh(stack)) {
            cir.setReturnValue(StaticGunRenderResult.UNSUPPORTED);
        }
    }
}
