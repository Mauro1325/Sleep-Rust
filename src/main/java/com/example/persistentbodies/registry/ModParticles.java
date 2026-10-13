package com.example.persistentbodies.registry;

import com.example.persistentbodies.PersistentBodies;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, PersistentBodies.MOD_ID);
    /** The floating "z" a sleeping body lets out. Its look (small z growing into a big Z) lives in the particle JSON. */
    public static final RegistryObject<SimpleParticleType> SLEEP_Z = PARTICLES.register("sleep_z", () -> new SimpleParticleType(false));

    private ModParticles() { }
}
