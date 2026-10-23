package com.example.taczmeshloader.render;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.tacz.guns.compat.iris.IrisCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris のシェーダーパック切り替えを検知し、全 {@link PolyMeshModel} の
 * VBO キャッシュを無効化するトラッカー。
 *
 * Fabric版: Fabric API の WorldRenderEvents.START（毎フレーム）で判定する。
 * TacZ 本体のイベントには依存しない（TacZ Refabricated の版によって
 * イベントクラスの場所が変わり、起動時クラッシュの原因になったため）。
 */
@Environment(EnvType.CLIENT)
public final class ShaderStateTracker {

    private static final boolean IRIS_LOADED =
            FabricLoader.getInstance().isModLoaded("iris");

    private static Boolean lastShaderState = null;

    private static final Set<PolyMeshModel> registeredModels =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ShaderStateTracker() {}

    /** TacZMeshLoaderMod から呼ばれ、Fabric のイベントシステムに登録する */
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
                for (PolyMeshModel model : registeredModels) {
                    model.invalidateVboCache();
                }
            }
        });
    }

    public static void registerModel(PolyMeshModel model) {
        if (model != null) {
            registeredModels.add(model);
        }
    }

    public static void unregisterModel(PolyMeshModel model) {
        registeredModels.remove(model);
    }
}
