package com.example.taczmeshloader.render;

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
 * Collects translucent render requests during a frame and flushes them all at once
 * after {@code AFTER_ENTITIES}, reducing draw calls when multiple poly_mesh items
 * exist in the world simultaneously.
 *
 * <p>Register with: {@code MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class)}</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MeshyBatchFlushHandler {

    // Render-thread only — no synchronization required.
    private static final Set<ResourceLocation> pendingTranslucentTextures = new HashSet<>();

    /** Called by render methods instead of endBatch() to defer the GPU flush. */
    public static void markTranslucentPending(ResourceLocation texture) {
        pendingTranslucentTextures.add(texture);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (pendingTranslucentTextures.isEmpty()) return;

        MultiBufferSource.BufferSource bufferSource =
                Minecraft.getInstance().renderBuffers().bufferSource();

        for (ResourceLocation texture : pendingTranslucentTextures)
            bufferSource.endBatch(RenderType.entityTranslucentCull(texture));

        pendingTranslucentTextures.clear();
    }
}
