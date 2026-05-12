package com.example.taczmeshloader.mixin;

import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor interface for {@link ClientAttachmentIndex#attachmentModel}.
 * Replaces raw Java reflection with a proper Mixin accessor.
 */
@Mixin(value = ClientAttachmentIndex.class, remap = false)
public interface ClientAttachmentIndexAccessor {
    @Accessor("attachmentModel")
    void setAttachmentModel(BedrockAttachmentModel model);
}
