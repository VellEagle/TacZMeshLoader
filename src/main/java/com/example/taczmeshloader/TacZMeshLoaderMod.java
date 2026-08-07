package com.example.taczmeshloader;

import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.render.ScreenRenderTracker;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.example.taczmeshloader.tacz.TaczMeshyIntegration;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TacZMeshLoaderMod implements ClientModInitializer {

    public static final String MOD_ID = "taczmeshloader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[TacZMeshLoader] Initialized.");

        TaczMeshyIntegration.onClientSetup();
        LOGGER.info("[TacZMeshLoader] Registered TacZ client setup listener.");

        MeshyBatchFlushHandler.register();
        LOGGER.info("[TacZMeshLoader] Registered batch flush handler.");

        ShaderStateTracker.register();
        LOGGER.info("[TacZMeshLoader] Registered shader state tracker.");

        ScreenRenderTracker.register();
        LOGGER.info("[TacZMeshLoader] Registered screen render tracker.");
    }
}

