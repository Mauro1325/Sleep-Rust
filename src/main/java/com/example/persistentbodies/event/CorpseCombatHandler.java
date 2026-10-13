package com.example.persistentbodies.event;

import com.example.persistentbodies.entity.PlayerCorpseEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Vanilla only allows critical hits against LivingEntity targets (Player#attack checks
 * "target instanceof LivingEntity"), and the body is a plain Entity. This applies the exact same
 * conditions through Forge's CriticalHitEvent so a falling, fully charged, non-sprinting hit does x1.5.
 */
public class CorpseCombatHandler {
    @SubscribeEvent
    public void onCriticalHit(CriticalHitEvent event) {
        if (!(event.getTarget() instanceof PlayerCorpseEntity))
            return;
        Player attacker = event.getEntity();
        boolean critical = attacker.getAttackStrengthScale(0.5F) > 0.9F
                && attacker.fallDistance > 0.0F
                && !attacker.onGround()
                && !attacker.onClimbable()
                && !attacker.isInWater()
                && !attacker.hasEffect(MobEffects.BLINDNESS)
                && !attacker.isPassenger()
                && !attacker.isSprinting();
        if (!critical)
            return;
        event.setResult(Event.Result.ALLOW);
        event.setDamageModifier(1.5F);
    }
}
