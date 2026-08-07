package com.example.taczmeshloader;

import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.example.taczmeshloader.tacz.TaczMeshyIntegration;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(TacZMeshLoaderMod.MOD_ID)
public class TacZMeshLoaderMod {

    public static final String MOD_ID = "taczmeshloader";
    private static final Logger LOGGER = LogUtils.getLogger();

    public TacZMeshLoaderMod(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("[TacZMeshLoader] Initialized.");

        if (FMLLoader.getDist() == Dist.CLIENT) {
            modEventBus.addListener(TaczMeshyIntegration::onClientSetup);
            LOGGER.info("[TacZMeshLoader] Registered TacZ client setup listener.");

            // フレームレベルの translucent バッチフラッシュハンドラを登録
            NeoForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
            LOGGER.info("[TacZMeshLoader] Registered batch flush handler.");

            // Iris シェーダー切り替え時に VBO キャッシュを無効化するトラッカーを登録
            NeoForge.EVENT_BUS.register(ShaderStateTracker.class);
            LOGGER.info("[TacZMeshLoader] Registered shader state tracker.");
        }
    }
}
