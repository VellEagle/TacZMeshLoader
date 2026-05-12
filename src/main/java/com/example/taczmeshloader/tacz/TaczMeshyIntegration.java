package com.example.taczmeshloader.tacz;

import com.tacz.guns.api.client.other.GunModelTypeManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * TacZ integration helper for MeshyLoader.
 * 
 * Registers "meshy" model type with TacZ's GunModelTypeManager.
 * Content creators only need to set "model_type": "meshy" in display JSON.
 */
@OnlyIn(Dist.CLIENT)
public class TaczMeshyIntegration {

    /**
     * Register on FMLClientSetupEvent.
     */
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(TaczPolyMeshGunModel::register);
    }
}
