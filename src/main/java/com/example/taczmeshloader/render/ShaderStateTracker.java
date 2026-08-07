package com.example.taczmeshloader.render;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.tacz.guns.compat.iris.IrisCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.fml.ModList;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Oculus のシェーダーパック切り替えを検知し、全 {@link PolyMeshModel} の
 * VBO キャッシュを無効化するトラッカー。
 *
 * <h3>問題</h3>
 * {@link com.example.taczmeshloader.core.PolyMesh} は描画パフォーマンスのため
 * ライトレベルごとに VBO を焼き込みキャッシュする。しかし Oculus がシェーダー
 * パックを切り替えると、シャドウパスの有無や法線行列の期待値が変わるため、
 * 古い VBO をそのまま使うとメッシュの影が反転して見える。
 *
 * <h3>解決策</h3>
 * レンダーティック開始時 ({@link TickEvent.RenderTickEvent} Phase.START) に
 * {@link OculusCompat#isUsingRenderPack()} の返値を前フレームと比較し、
 * 変化があれば登録済みの全 {@link PolyMeshModel#invalidateVboCache()} を呼ぶ。
 * これにより次フレームで VBO が正しい状態で再生成される。
 *
 * <h3>登録方法</h3>
 * {@code TacZMeshLoaderMod} のコンストラクタで
 * {@code MinecraftForge.EVENT_BUS.register(ShaderStateTracker.class);} を呼ぶこと。
 */
@OnlyIn(Dist.CLIENT)
@net.neoforged.fml.common.EventBusSubscriber(value = Dist.CLIENT)
public final class ShaderStateTracker {

    /** Oculus がロードされていない場合はトラッキング不要 */
    private static final boolean IRIS_LOADED =
            ModList.get().isLoaded("iris");

    /**
     * 前フレームのシェーダーパック使用状態。
     * 初回は "未初期化" を表すため null を使う。
     */
    private static Boolean lastShaderState = null;

    /**
     * 登録済みの全 PolyMeshModel を WeakReference で保持する。
     * モデルが GC されても自動的にセットから消えるため、手動登録解除は不要。
     */
    private static final Set<PolyMeshModel> registeredModels =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ShaderStateTracker() {}

    /**
     * PolyMeshModel をシェーダー状態監視対象として登録する。
     * {@link com.example.taczmeshloader.tacz.TaczPolyMeshGunModel#loadPolyMesh} および
     * {@link com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel#loadPolyMesh}
     * の末尾から呼ばれる。
     *
     * @param model 監視対象のモデル（null は無視）
     */
    public static void register(PolyMeshModel model) {
        if (model != null) {
            registeredModels.add(model);
        }
    }

    /**
     * 登録を解除する。{@link PolyMeshModel#close()} と連動して呼ぶ。
     *
     * @param model 解除するモデル
     */
    public static void unregister(PolyMeshModel model) {
        registeredModels.remove(model);
    }

    /**
     * レンダーティック開始時に Oculus のシェーダー状態を確認し、
     * 変化があれば全モデルの VBO キャッシュを無効化する。
     *
     * {@link } は Forge 1.19.4 以降で削除されたため、
     * 代わりに {@link TickEvent.RenderTickEvent} (Phase.START) を使用する。
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (!IRIS_LOADED) return;
        if (registeredModels.isEmpty()) return;

        boolean currentState = IrisCompat.isUsingRenderPack();

        if (lastShaderState == null) {
            lastShaderState = currentState;
            return;
        }

        if (lastShaderState != currentState) {
            lastShaderState = currentState;
            for (PolyMeshModel model : registeredModels) {
                model.invalidateVboCache();
            }
        }
    }
}
