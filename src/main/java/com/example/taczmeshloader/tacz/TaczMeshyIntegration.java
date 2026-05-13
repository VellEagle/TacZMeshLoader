package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.MeshyModelRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Registers the {@code "meshy"} model type with TacZ's {@code GunModelTypeManager}.
 *
 * <p>Addon authors only need to set {@code "model_type": "meshy"} in their display JSON.
 * No Java code required on the addon side.</p>
 *
 * <pre>{@code
 * // guns/display/mygun_display.json
 * {
 *   "model_type": "meshy",
 *   "model": "mypack:models/gun/mygun_geo.json",
 *   "texture": "mypack:textures/gun/uv/mygun.png",
 *   "animation": "mypack:animations/mygun.animation.json"
 * }
 * }</pre>
 */
@OnlyIn(Dist.CLIENT)
public class TaczMeshyIntegration {

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(TaczPolyMeshGunModel::register);
    }

    /**
     * Registers a resource reload listener so that F3+T properly invalidates all
     * cached poly_mesh paths and forces geometry to be re-loaded from disk.
     */
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (barrier, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor) ->
                        barrier.wait(null).thenRunAsync(MeshyModelRegistry::invalidateAll, gameExecutor)
        );
    }
}
