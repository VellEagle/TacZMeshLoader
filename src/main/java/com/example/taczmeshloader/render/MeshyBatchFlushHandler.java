package com.example.taczmeshloader.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * フレームの終わりに translucent バッファをまとめてフラッシュするハンドラ。
 *
 * NeoForge 版との対応:
 *   @SubscribeEvent + RenderLevelStageEvent.AFTER_ENTITIES
 *   → WorldRenderEvents.AFTER_ENTITIES (Fabric)
 */
@Environment(EnvType.CLIENT)
public class MeshyBatchFlushHandler {

    private static final Set<ResourceLocation> pendingTranslucentTextures = new HashSet<>();

    /** TacZMeshLoaderMod から呼ばれ、Fabric のイベントシステムに登録する */
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

    /**
     * TaczPolyMeshGunModel.render() などから呼ばれる。
     * endBatch() は呼ばず、テクスチャを記録だけする。
     */
    public static void markTranslucentPending(ResourceLocation texture) {
        pendingTranslucentTextures.add(texture);
    }
}
