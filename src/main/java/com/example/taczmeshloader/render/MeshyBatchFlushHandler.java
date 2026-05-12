package com.example.taczmeshloader.render;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * Frame-level translucent batch flush handler.
 * Defers endBatch() calls to end of frame, reducing N draw calls to 1.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MeshyBatchFlushHandler {

    /**
     * Thread-safe texture tracking for deferred translucent rendering.
     */
    private static final ThreadLocal<Set<ResourceLocation>> pendingTranslucentTextures = 
            ThreadLocal.withInitial(HashSet::new);

    /**
     * Mark texture for deferred batch flush at frame end.
     */
    public static void markTranslucentPending(ResourceLocation texture) {
        pendingTranslucentTextures.get().add(texture);
    }

    /**
     * Flush all pending translucent batches after entity rendering.
     * Reduces N draw calls to 1 per frame.
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        
        Set<ResourceLocation> textures = pendingTranslucentTextures.get();
        if (textures.isEmpty()) return;

        MultiBufferSource.BufferSource bufferSource =
                Minecraft.getInstance().renderBuffers().bufferSource();

        for (ResourceLocation texture : textures) {
            bufferSource.endBatch(RenderType.entityTranslucentCull(texture));
        }
        textures.clear();
    }
}
