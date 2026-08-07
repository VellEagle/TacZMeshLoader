package com.example.taczmeshloader.render;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * フレームの終わりに translucent バッファをまとめてフラッシュするハンドラ。
 *
 * <h3>問題</h3>
 * TaczPolyMeshGunModel.render() は translucent 頂点を毎回バッファに書き込む。
 * アイテムが N 個あると N 回 endBatch() が呼ばれていた。
 *
 * <h3>解決策</h3>
 * render() では endBatch() を呼ばず、このハンドラがフレーム終了時に
 * 使用済みテクスチャの translucent バッファをまとめて 1 回フラッシュする。
 *
 * 結果: 地面に銃 N 個 → translucent endBatch は N 回 → 1 回に削減。
 *
 * <h3>登録方法</h3>
 * TacZMeshLoaderMod の FMLClientSetupEvent か ForgeEventBus に以下を登録:
 * <pre>{@code
 * MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
 * }</pre>
 */
@OnlyIn(Dist.CLIENT)
@net.neoforged.fml.common.EventBusSubscriber(value = Dist.CLIENT)
public class MeshyBatchFlushHandler {

    /**
     * フレームごとに使われた translucent テクスチャを追跡するセット。
     * スレッドセーフは不要（レンダースレッドのみ使用）。
     */
    private static final Set<ResourceLocation> pendingTranslucentTextures = new HashSet<>();

    /**
     * TaczPolyMeshGunModel.render() から呼ばれる。
     * endBatch() は呼ばず、テクスチャを記録だけする。
     */
    public static void markTranslucentPending(ResourceLocation texture) {
        pendingTranslucentTextures.add(texture);
    }

    /**
     * RenderLevelStage.AFTER_ENTITIES の後に translucent バッファをまとめてフラッシュ。
     * これにより N 個のアイテムの translucent を 1 回のドローコールで描画できる。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (pendingTranslucentTextures.isEmpty()) return;

        MultiBufferSource.BufferSource bufferSource =
                Minecraft.getInstance().renderBuffers().bufferSource();

        for (ResourceLocation texture : pendingTranslucentTextures) {
            bufferSource.endBatch(RenderType.entityTranslucentCull(texture));
        }
        pendingTranslucentTextures.clear();
    }
}
