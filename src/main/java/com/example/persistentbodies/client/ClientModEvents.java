package com.example.persistentbodies.client;

import com.example.persistentbodies.PersistentBodies;
import com.example.persistentbodies.registry.ModEntities;
import com.example.persistentbodies.registry.ModMenus;
import com.example.persistentbodies.registry.ModParticles;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = PersistentBodies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() { }

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PlayerCorpseModel.LAYER_LOCATION, PlayerCorpseModel::createBodyLayer);
        event.registerLayerDefinition(PlayerCorpseModel.SLIM_LAYER_LOCATION, PlayerCorpseModel::createSlimBodyLayer);
        event.registerLayerDefinition(PlayerCorpseModel.CAPE_LAYER_LOCATION, PlayerCorpseModel::createCapeBodyLayer);
        event.registerLayerDefinition(PlayerCorpseModel.ARMOR_LAYER_LOCATION, PlayerCorpseModel::createArmorBodyLayer);
        event.registerLayerDefinition(PlayerCorpseModel.SLIM_ARMOR_LAYER_LOCATION, PlayerCorpseModel::createSlimArmorBodyLayer);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PLAYER_CORPSE.get(), PlayerCorpseRenderer::new);
    }

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.SLEEP_Z.get(), SleepZParticle.Provider::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModMenus.CORPSE_MENU.get(), CorpseScreen::new));
    }
}
