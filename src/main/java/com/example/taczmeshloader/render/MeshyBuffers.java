package com.example.taczmeshloader.render;

import com.tacz.guns.compat.iris.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * メッシュ描画で使う描画バッファの書き出し（フラッシュ）処理をまとめた補助クラス。
 *
 * <h3>TacZ 1.2.0（公式 NeoForge 版）対応で追加</h3>
 * TacZ 1.2.0 からは、銃・アタッチメントの描画メソッドに「描画先のバッファ」が
 * 引数で渡されるようになった（一人称＋Iris 時は Iris の手部用バッファ、
 * それ以外は通常 Minecraft 全体のバッファ）。
 * 以前のように Minecraft 全体のバッファを決め打ちで使うと、TacZ と別のバッファに
 * 描くことになるため、渡されたバッファをそのまま使い、ここでまとめて書き出す。
 */
@OnlyIn(Dist.CLIENT)
public final class MeshyBuffers {

    private MeshyBuffers() {}

    /** 指定した RenderType の分だけ書き出す（書き出せない種類のバッファなら何もしない）。 */
    public static void endBatch(MultiBufferSource buffer, RenderType renderType) {
        if (buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(renderType);
        }
    }

    /** 不透明（カットアウト）メッシュの書き出し。Iris 導入時は Iris 側の書き出しを優先する。 */
    public static void flushCutout(MultiBufferSource buffer, ResourceLocation texture) {
        if (!IrisCompat.endBatch(buffer)) {
            endBatch(buffer, RenderType.entityCutoutNoCull(texture));
            endBatch(buffer, RenderType.entityCutout(texture));
        }
    }

    /**
     * 半透明メッシュの書き出し。
     * Minecraft 全体のバッファに描いた場合（かつ Iris 未導入）だけは、従来通り
     * フレームの最後にまとめて書き出す（{@link MeshyBatchFlushHandler}）。
     * TacZ から別のバッファを渡された場合は、その場で書き出す。
     */
    public static void flushTranslucent(MultiBufferSource buffer, ResourceLocation texture) {
        if (IrisCompat.endBatch(buffer)) return;
        if (!ModList.get().isLoaded("iris") && buffer == Minecraft.getInstance().renderBuffers().bufferSource()) {
            MeshyBatchFlushHandler.markTranslucentPending(texture);
        } else {
            endBatch(buffer, RenderType.entityTranslucentCull(texture));
        }
    }
}
