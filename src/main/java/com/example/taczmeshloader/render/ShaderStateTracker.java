package com.example.taczmeshloader.render;

import cn.sh1rocu.tacz.api.event.RenderTickEvent;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.tacz.guns.compat.iris.IrisCompat;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Iris のシェーダーパック切り替えを検知し、全 PolyMeshModel の
 * VBO キャッシュを無効化するトラッカー。
 * Fabric版: RenderTickEvent.CALLBACK を使用。
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
            registeredModels.add(model);
        }
    }

    public static void unregister(PolyMeshModel model) {
        registeredModels.remove(model);
    }

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
}
