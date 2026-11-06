package com.example.taczmeshloader.render;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.tacz.guns.compat.iris.IrisCompat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris のシェーダーパック切り替えを検知し、全 PolyMeshModel の
 * VBO キャッシュを無効化するトラッカー。
 * Fabric版: Fabric API の WorldRenderEvents.START（毎フレーム）で判定する。
 * TacZ 本体のイベントには依存しない（TacZ Refabricated 0.7.1 で
 * RenderTickEvent の場所が変わり、起動時クラッシュの原因になったため）。
 */
public final class ShaderStateTracker {

    private static final String IRIS_MOD_ID = "iris";
    private static final boolean IRIS_LOADED =
            FabricLoader.getInstance().isModLoaded(IRIS_MOD_ID);

    private static Boolean lastShaderState = null;

    private static final Set<PolyMeshModel> registeredModels =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ShaderStateTracker() {}

    public static void register(PolyMeshModel model) {
        if (model != null) {
            // 【TacZ 1.2.0 対応】TacZ 1.2.0 はモデルを別スレッドで読み込むため、同時アクセスを防ぐ
            synchronized (registeredModels) {
                registeredModels.add(model);
            }
        }
    }

    public static void unregister(PolyMeshModel model) {
        synchronized (registeredModels) {
            registeredModels.remove(model);
        }
    }

    public static void register() {
        // 【0.1.9 修正】以前は TacZ Refabricated 内部の RenderTickEvent
        // (cn.sh1rocu.tacz.api.event.RenderTickEvent) を使っていたが、
        // TacZ Refabricated 0.7.1 でこのクラスが SimpleBedrockModel 側
        // (cn.sh1rocu.simplebedrockmodel.api.event.RenderTickEvent) へ移動したため、
        // 起動時に NoClassDefFoundError でクラッシュしていた。
        // TacZ の内部仕様の変更に左右されないよう、Fabric API 標準の
        // WorldRenderEvents.START（毎フレーム、ワールド描画の直前に呼ばれる）に置き換えた。
        WorldRenderEvents.START.register(context -> {
            if (!IRIS_LOADED) return;
            if (registeredModels.isEmpty()) return;

            boolean currentState = IrisCompat.isUsingRenderPack();

            if (lastShaderState == null) {
                lastShaderState = currentState;
                return;
            }

            if (lastShaderState != currentState) {
                lastShaderState = currentState;
                // 【TacZ 1.2.0 対応】別スレッドからの登録とぶつからないよう、一覧のコピーを取ってから処理する
                java.util.List<PolyMeshModel> snapshot;
                synchronized (registeredModels) {
                    snapshot = new java.util.ArrayList<>(registeredModels);
                }
                for (PolyMeshModel model : snapshot) {
                    model.invalidateVboCache();
                }
            }
        });
    }
}
