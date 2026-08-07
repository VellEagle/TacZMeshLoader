package com.example.taczmeshloader.render;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * フレームの終わりに translucent バッファをまとめてフラッシュするハンドラ。
 * Fabric版: WorldRenderEvents.AFTER_ENTITIES を使用。
 */
public class MeshyBatchFlushHandler {

    private static final Set<ResourceLocation> pendingTranslucentTextures = new HashSet<>();

    public static void markTranslucentPending(ResourceLocation texture) {
        pendingTranslucentTextures.add(texture);
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (pendingTranslucentTextures.isEmpty()) return;
            MultiBufferSource.BufferSource bufferSource =
                    Minecraft.getInstance().renderBuffers().bufferSource();
            for (ResourceLocation texture : pendingTranslucentTextures) {
                bufferSource.endBatch(RenderType.entityTranslucentCull(texture));
            }
            pendingTranslucentTextures.clear();
        });
    }
}
