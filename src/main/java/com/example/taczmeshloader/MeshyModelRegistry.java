package com.example.taczmeshloader;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks all active poly_mesh model instances via WeakReferences.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Provides {@link #invalidateAll()} for the resource reload listener so F3+T
 *       properly re-triggers poly_mesh loading on all live models.</li>
 *   <li>Exposes aggregate stats for the F3 debug overlay.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MeshyModelRegistry {

    private static final List<WeakReference<TaczPolyMeshGunModel>>        gunModels        = new ArrayList<>();
    private static final List<WeakReference<TaczPolyMeshAttachmentModel>> attachmentModels = new ArrayList<>();

    public static synchronized void registerGunModel(TaczPolyMeshGunModel model) {
        gunModels.add(new WeakReference<>(model));
    }

    public static synchronized void registerAttachmentModel(TaczPolyMeshAttachmentModel model) {
        attachmentModels.add(new WeakReference<>(model));
    }

    /**
     * Called by the resource reload listener (F3+T).
     * Resets the cached path on all live models so the mixin re-loads geometry on the next frame.
     */
    public static synchronized void invalidateAll() {
        for (WeakReference<TaczPolyMeshGunModel> ref : gunModels) {
            TaczPolyMeshGunModel m = ref.get();
            if (m != null) m.invalidatePolyMesh();
        }
        for (WeakReference<TaczPolyMeshAttachmentModel> ref : attachmentModels) {
            TaczPolyMeshAttachmentModel m = ref.get();
            if (m != null) m.invalidatePolyMesh();
        }
        cleanup();
    }

    /** Returns [gunCount, attachmentCount, totalVertices, vboCacheEntries]. */
    public static synchronized int[] getStats() {
        int guns = 0, attachments = 0, verts = 0, vboEntries = 0;
        for (WeakReference<TaczPolyMeshGunModel> ref : gunModels) {
            TaczPolyMeshGunModel m = ref.get();
            if (m != null && m.hasPolyMesh()) {
                guns++;
                verts      += m.getPolyMeshVertexCount();
                vboEntries += m.getPolyMeshVboCacheSize();
            }
        }
        for (WeakReference<TaczPolyMeshAttachmentModel> ref : attachmentModels) {
            TaczPolyMeshAttachmentModel m = ref.get();
            if (m != null && m.hasPolyMesh()) {
                attachments++;
                verts      += m.getPolyMeshVertexCount();
                vboEntries += m.getPolyMeshVboCacheSize();
            }
        }
        return new int[]{guns, attachments, verts, vboEntries};
    }

    // F3 debug overlay
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!Minecraft.getInstance().options.renderDebug) return;
        int[] s = getStats();
        event.getRight().add("");
        event.getRight().add("\u00a76[TacZMeshLoader]");
        event.getRight().add(String.format("\u00a77Guns: %d | Attachments: %d", s[0], s[1]));
        event.getRight().add(String.format("\u00a77Vertices: %,d | VBO entries: %d", s[2], s[3]));
    }

    private static void cleanup() {
        gunModels.removeIf(r        -> r.get() == null);
        attachmentModels.removeIf(r -> r.get() == null);
    }
}
