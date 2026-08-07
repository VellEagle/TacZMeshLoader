package com.example.taczmeshloader.render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 「今まさに GUI 画面（Screen#render()）を描画している瞬間」を検出するトラッカー。
 *
 * <h3>問題</h3>
 * これまで {@code Minecraft.getInstance().screen != null}（＝メニューが
 * 「開いている」かどうか）を使って、インベントリのプレイヤードール表示など
 * GUI 埋め込みの3D描画で VBO を無効化する判定に使っていた。
 *
 * しかし、メニューが開いている間もワールド（地面ドロップ・額縁のアイテム・
 * 他プレイヤーが持つ銃など）は引き続き毎フレーム描画される。
 * {@code screen != null} はメニューが開いている「間ずっと」true になるため、
 * ワールド内の無関係な描画にまで VBO 無効化が適用されてしまい、
 * メッシュ銃が多いと「メニューを開いた瞬間に画面内の全メッシュ銃が
 * 一斉に重い経路に切り替わる」という深刻な性能劣化を引き起こしていた。
 *
 * <h3>解決策</h3>
 * ワールド描画（{@code LevelRenderer}）と GUI 描画（{@code Screen#render()}）は
 * 同じフレーム内でも別々のタイミングで行われる。{@link ScreenEvent.Render.Pre} /
 * {@link ScreenEvent.Render.Post} で "今まさに Screen#render() の中にいるか" を
 * 検出すれば、インベントリのプレイヤードール表示（Screen#render() の内部で
 * 描画される）だけを正確に狙い撃ちでき、ワールド内の無関係な描画には
 * 影響しない。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ScreenRenderTracker {

    private static volatile boolean renderingScreen = false;

    private ScreenRenderTracker() {}

    /**
     * 現在 Screen#render()（GUI画面自体の描画。インベントリのプレイヤー
     * ドール表示等を含む）の実行中かどうか。
     *
     * <p>{@code Minecraft.getInstance().screen != null} とは異なり、
     * こちらは実際に GUI 描画が実行されている「瞬間」だけ true になる。</p>
     */
    public static boolean isRenderingScreen() {
        return renderingScreen;
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        renderingScreen = true;
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        renderingScreen = false;
    }
}
