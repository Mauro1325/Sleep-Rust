package com.example.persistentbodies.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * The "z z Z" of a sleeping body: it starts as a small z, drifts up swaying from side to side, grows into a big Z
 * (the sprite changes with the age of the particle) and fades out at the end.
 */
public class SleepZParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float swayPhase;

    protected SleepZParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.sprites = sprites;
        this.lifetime = 55 + random.nextInt(15);
        this.quadSize = 0.17F;
        this.gravity = 0.0F;
        this.friction = 0.96F;
        this.hasPhysics = false;
        this.xd = 0.0D;
        this.yd = 0.025D;
        this.zd = 0.0D;
        this.alpha = 0.0F;
        this.swayPhase = random.nextFloat() * 6.2831855F;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) return;
        setSpriteFromAge(sprites);
        // Gentle side-to-side drift.
        double sway = Math.sin(age * 0.17D + swayPhase) * 0.0035D;
        this.xd += sway;
        this.zd += Math.cos(age * 0.13D + swayPhase) * 0.002D;
        // Fade in quickly, fade out during the last third of its life.
        float life = age / (float) lifetime;
        this.alpha = life < 0.12F ? life / 0.12F : life > 0.67F ? Math.max(0.0F, (1.0F - life) / 0.33F) : 1.0F;
    }

    @Override
    public float getQuadSize(float partialTick) {
        float life = Math.min(1.0F, (age + partialTick) / lifetime);
        return quadSize * (0.75F + life * 0.65F);
    }

    @Override
    protected int getLightColor(float partialTick) {
        return 15728880; // full bright: the z must stay readable at night
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                double xSpeed, double ySpeed, double zSpeed) {
            return new SleepZParticle(level, x, y, z, sprites);
        }
    }
}
