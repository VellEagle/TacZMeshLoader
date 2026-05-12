package com.example.taczmeshloader;

import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.tacz.TaczMeshyIntegration;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(TacZMeshLoaderMod.MOD_ID)
public class TacZMeshLoaderMod {

    public static final String MOD_ID = "taczmeshloader";
    private static final Logger LOGGER = LogUtils.getLogger();

    public TacZMeshLoaderMod() {
        LOGGER.info("[TacZMeshLoader] Initialized.");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus()
                    .addListener(TaczMeshyIntegration::onClientSetup);
            LOGGER.info("[TacZMeshLoader] Registered TacZ client setup listener.");

            // フレームレベルの translucent バッチフラッシュハンドラを登録
            // これにより地面に複数の銃を落としても translucent は 1 フレームに 1 回だけフラッシュされる
            MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
            LOGGER.info("[TacZMeshLoader] Registered batch flush handler.");
        }
    }
}
