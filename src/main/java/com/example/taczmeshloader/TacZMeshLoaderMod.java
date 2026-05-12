package com.example.taczmeshloader;

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

    // Constructor for Forge 47.3+ (with IEventBus injection)
    public TacZMeshLoaderMod(IEventBus modEventBus) {
        init(modEventBus);
    }

    // Fallback constructor for older Forge versions
    public TacZMeshLoaderMod() {
        init(null);
    }

    private void init(IEventBus modEventBus) {
        LOGGER.info("[TacZMeshLoader] Initialized.");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Use injected event bus if available, otherwise get it the old way
            IEventBus eventBus = modEventBus;
            if (eventBus == null) {
                try {
                    Class<?> contextClass = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext");
                    Object context = contextClass.getMethod("get").invoke(null);
                    eventBus = (IEventBus) contextClass.getMethod("getModEventBus").invoke(context);
                } catch (Exception e) {
                    LOGGER.error("[TacZMeshLoader] Failed to get mod event bus", e);
                    return;
                }
            }

            eventBus.addListener(TaczMeshyIntegration::onClientSetup);
            LOGGER.info("[TacZMeshLoader] Registered TacZ client setup listener.");

            MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
            LOGGER.info("[TacZMeshLoader] Registered batch flush handler.");
        }
    }
}
