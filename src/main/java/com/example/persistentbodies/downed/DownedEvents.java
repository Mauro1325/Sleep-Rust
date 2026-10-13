package com.example.persistentbodies.downed;

import com.example.persistentbodies.config.DownedConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge event wiring of the downed system (server logic only; every handler ignores the client side).
 * Logout/login are NOT handled here: PlayerConnectionHandler calls DownedManager from a single place so the order
 * "downed death first, body logic second" is deterministic.
 */
public class DownedEvents {

    private static boolean downed(Object entity) {
        return entity instanceof ServerPlayer p && DownedManager.isDowned(p.getUUID());
    }

    private static boolean bypasses(DamageSource source) {
        return source.is(DamageTypeTags.BYPASSES_INVULNERABILITY); // /kill, void: never downed, never blocked
    }

    // ---- ALIVE -> DOWNED: lethal damage is turned into the downed state ---------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (DownedManager.isDowned(p.getUUID())) return; // a downed player dying (/kill, void, bleed-out): real death
        if (DownedManager.isFinishing(p.getUUID())) return; // being finished off by another player: real death
        if (bypasses(event.getSource())) return;
        if (DownedManager.tryDown(p, event.getSource())) event.setCanceled(true);
    }

    /** Any real death clears every piece of downed state (runs only if nothing cancelled the death). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeathCleanup(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) DownedManager.onDeath(p);
    }

    // ---- downed / freshly revived players cannot be hurt or healed -------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttacked(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || bypasses(event.getSource())) return;
        if (DownedManager.isDowned(p.getUUID()) || DownedManager.isProtected(p.getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onHeal(LivingHealEvent event) {
        if (downed(event.getEntity())) event.setCanceled(true); // no regeneration / potion healing while downed
    }

    @SubscribeEvent
    public void onTarget(LivingChangeTargetEvent event) {
        if (DownedConfig.MOBS_IGNORE_DOWNED.get() && downed(event.getNewTarget())) event.setNewTarget(null);
    }

    /** A reviver who takes damage loses concentration. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onReviverHurt(LivingHurtEvent event) {
        if (!DownedConfig.CANCEL_ON_REVIVER_DAMAGE.get() || event.isCanceled()) return;
        if (event.getEntity() instanceof ServerPlayer p && event.getAmount() > 0.0F)
            ReviveManager.cancelInvolving(p.getServer(), p.getUUID(), "message.persistentbodies.revive_cancelled_hurt");
    }

    // ---- downed players cannot act ------------------------------------------------------------------------------------

    /** Downed players cannot interact; a reviver holding right click must not also eat / draw a bow / place blocks. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.isCancelable()) return;
        if (downed(event.getEntity())
                || (event.getEntity() instanceof ServerPlayer p && ReviveManager.isReviving(p.getUUID())))
            event.setCanceled(true);
    }

    /**
     * Hitting a downed player never does normal damage: it finishes them off (left click), when the config and the
     * server's PvP setting allow it. A downed player cannot attack at all.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event) {
        if (downed(event.getEntity())) { event.setCanceled(true); return; }
        if (event.getTarget() instanceof ServerPlayer victim && event.getEntity() instanceof ServerPlayer attacker
                && victim != attacker && DownedManager.isDowned(victim.getUUID())) {
            event.setCanceled(true);
            if (DownedConfig.ALLOW_FINISH_OFF.get() && attacker.isAlive() && !attacker.isSpectator()
                    && attacker.getServer().isPvpAllowed())
                DownedManager.finish(victim, attacker);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItem(LivingEntityUseItemEvent.Start event) {
        if (downed(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onToss(ItemTossEvent event) {
        if (downed(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPickup(EntityItemPickupEvent event) {
        if (downed(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (downed(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (downed(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMount(EntityMountEvent event) {
        if (event.isMounting() && downed(event.getEntityMounting())) event.setCanceled(true);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer p) DownedManager.tickPlayer(p);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ReviveManager.tick(event.getServer());
        DownedManager.tickLogin(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        DownedManager.clearAll();
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p)
            ReviveManager.cancelInvolving(p.getServer(), p.getUUID(), "message.persistentbodies.revive_too_far");
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) DownedManager.forget(p);
    }
}
