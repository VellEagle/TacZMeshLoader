package com.example.taczmeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Function;

import static net.minecraft.Util.memoize;

/**
 * Custom render types for MeshyLoader.
 * 
 * translucentGlass: For canopy/glass-like translucent meshes.
 *   - Depth test: LEQUAL (respects depth ordering)
 *   - Depth write: disabled (COLOR_WRITE only)
 *   - Blend: SRC_ALPHA / ONE_MINUS_SRC_ALPHA
 *   - Culling: disabled (double-sided)
 */
@OnlyIn(Dist.CLIENT)
public final class MeshyRenderTypes extends RenderType {

    private MeshyRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                              int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                              Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_GLASS =
            memoize(texture -> create(
                    "meshy_translucent_glass",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, true,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)
            ));

    public static RenderType translucentGlass(ResourceLocation texture) {
        return TRANSLUCENT_GLASS.apply(texture);
    }
}
