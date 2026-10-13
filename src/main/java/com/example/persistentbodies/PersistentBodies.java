package com.example.persistentbodies;

import com.example.persistentbodies.config.CorpseConfig;
import com.example.persistentbodies.event.PlayerConnectionHandler;
import com.example.persistentbodies.event.CorpseBlockProtection;
import com.example.persistentbodies.event.CorpseCombatHandler;
import com.example.persistentbodies.registry.ModEntities;
import com.example.persistentbodies.registry.ModMenus;
import com.example.persistentbodies.registry.ModParticles;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(PersistentBodies.MOD_ID)
public class PersistentBodies {
    public static final String MOD_ID = "persistentbodies";
    public PersistentBodies() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(bus);
        ModMenus.MENUS.register(bus);
        ModParticles.PARTICLES.register(bus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CorpseConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new PlayerConnectionHandler());
        MinecraftForge.EVENT_BUS.register(new CorpseBlockProtection());
        MinecraftForge.EVENT_BUS.register(new CorpseCombatHandler());
    }
}
