package com.example.persistentbodies.registry;

import com.example.persistentbodies.PersistentBodies;
import com.example.persistentbodies.entity.PlayerCorpseEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, PersistentBodies.MOD_ID);
    public static final RegistryObject<EntityType<PlayerCorpseEntity>> PLAYER_CORPSE = ENTITY_TYPES.register("player_corpse",
        () -> EntityType.Builder.<PlayerCorpseEntity>of(PlayerCorpseEntity::new, MobCategory.MISC).sized(0.6F, 0.35F).clientTrackingRange(8).build("player_corpse"));
    private ModEntities() { }
}
