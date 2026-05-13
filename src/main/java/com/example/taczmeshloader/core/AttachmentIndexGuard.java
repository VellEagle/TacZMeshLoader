package com.example.taczmeshloader.core;

import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.WeakHashMap;

/**
 * Tracks (ClientAttachmentIndex → geoPath) pairs that have already had their poly_mesh loaded.
 * Kept in the core package (NOT in mixin.*) to avoid Mixin's package isolation restriction,
 * which prohibits non-mixin classes in the mixin package from being referenced by external code.
 */
public final class AttachmentIndexGuard {

    public static final WeakHashMap<ClientAttachmentIndex, ResourceLocation> PROCESSED =
            new WeakHashMap<>();

    /** Clears the guard — call on F3+T resource reload so geometry is re-loaded. */
    public static synchronized void clear() {
        PROCESSED.clear();
    }

    private AttachmentIndexGuard() {}
}
