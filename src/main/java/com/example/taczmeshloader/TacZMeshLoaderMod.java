package com.example.taczmeshloader;

import com.example.taczmeshloader.MeshyModelRegistry;
import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.tacz.TaczMeshyIntegration;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(TacZMeshLoaderMod.MOD_ID)
public class TacZMeshLoaderMod {

    public static final String MOD_ID = "taczmeshloader";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Forge 47.3+ — IEventBus injected directly by the mod loader. */
    public TacZMeshLoaderMod(IEventBus modEventBus) {
        init(modEventBus);
    }

    /** Legacy fallback for Forge < 47.3. FMLJavaModLoadingContext.get() is deprecated but intentional here. */
    @SuppressWarnings("removal")
    public TacZMeshLoaderMod() {
        IEventBus modEventBus;
        try {
            modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
                    .get().getModEventBus();
        } catch (Exception e) {
            LOGGER.error("[TacZMeshLoader] Failed to obtain mod event bus", e);
            return;
        }
        init(modEventBus);
    }

    private void init(IEventBus modEventBus) {
        LOGGER.info("[TacZMeshLoader] Initialized.");
        if (FMLEnvironment.dist != Dist.CLIENT) return;

        modEventBus.addListener(TaczMeshyIntegration::onClientSetup);
        modEventBus.addListener(TaczMeshyIntegration::onRegisterReloadListeners);
        // Translucent batch flush + F3 debug overlay
        MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
        MinecraftForge.EVENT_BUS.register(MeshyModelRegistry.class);
        LOGGER.info("[TacZMeshLoader] Client listeners registered.");
    }
}
