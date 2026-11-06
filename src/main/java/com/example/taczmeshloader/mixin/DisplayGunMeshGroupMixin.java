package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.MeshPresence;
import com.tacz.guns.block.entity.GunDisplayBlockEntity;
import com.tacz.guns.client.renderer.block.DisplayGunMeshGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【TacZ 1.2.0（公式 NeoForge 版）対応】展示ケース・ペグボードに飾った銃の「VBO 焼き込み描画」を、
 * メッシュを使う銃に限って断る（false = TacZ に任せず通常描画）。
 *
 * <p>false を返すと、展示ケース/ペグボードの描画処理が通常のアイテム描画に戻るため、
 * TML がメッシュを描けるようになる。詳細は {@link MeshPresence} を参照。</p>
 *
 * <p>require = 0：TacZ の今後の更新でこの処理が無くなっても、クラッシュはさせない。</p>
 */
@Mixin(value = DisplayGunMeshGroup.class, remap = false)
public class DisplayGunMeshGroupMixin {

    @Inject(method = "tryRender", at = @At("HEAD"), cancellable = true, require = 0)
    private static void taczmeshloader$skipMeshGuns(GunDisplayBlockEntity owner, int index, int overlay,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (MeshPresence.gunUsesMesh(owner.getItem(index))) {
            cir.setReturnValue(false);
        }
    }
}
