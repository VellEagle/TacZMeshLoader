package com.example.taczmeshloader.render;

import cn.sh1rocu.tacz.api.event.RenderTickEvent;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.tacz.guns.compat.iris.IrisCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris のシェーダーパック切り替えを検知し、全 {@link PolyMeshModel} の
 * VBO キャッシュを無効化するトラッカー。
 *
 * 1.20.1 版と同じ {@link RenderTickEvent}（TacZ 本体提供）を使用する。
 * （以前は NeoForge の RenderGuiEvent.Pre に 1:1 対応させる形で
 * HudRenderCallback を使っていたが、Fabric API 側で非推奨化されたため
 * 1.20.1 版の実装に統一した）
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
        RenderTickEvent.CALLBACK.register(event -> {
            if (event.phase != RenderTickEvent.Phase.START) return;
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
