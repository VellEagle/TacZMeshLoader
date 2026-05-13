package com.example.taczmeshloader.core;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;

import java.util.WeakHashMap;

/**
 * Caches TaczPolyMeshAttachmentModel instances per ClientAttachmentIndex.
 *
 * <p>Two-tier strategy:
 * <ol>
 *   <li>Model cache: loadPolyMesh is called exactly once per index — the created model is
 *       stored here. Prevents the per-frame freeze that occurs when loadPolyMesh reads
 *       from disk on every checkTextureAndModel call.</li>
 *   <li>Model assignment (setAttachmentModel) is done on EVERY checkTextureAndModel call,
 *       because TacZ may reset the attachmentModel field between render frames.</li>
 * </ol>
 */
public final class AttachmentIndexGuard {

    /** Stores the created model for each index so loadPolyMesh is not repeated. */
    public static final WeakHashMap<ClientAttachmentIndex, TaczPolyMeshAttachmentModel> MODEL_CACHE =
            new WeakHashMap<>();

    /** Clears the cache — call on F3+T reload so models are re-created with new geometry. */
    public static synchronized void clear() {
        MODEL_CACHE.clear();
    }

    private AttachmentIndexGuard() {}
}
