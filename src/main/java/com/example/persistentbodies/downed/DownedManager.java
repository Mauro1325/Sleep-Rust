package com.example.persistentbodies.downed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.example.persistentbodies.config.DownedConfig;
import com.example.persistentbodies.compat.PlayerReviveBridge;
import com.example.persistentbodies.logic.DownedRegistry;
import com.example.persistentbodies.network.DownedNetwork;
import com.example.persistentbodies.storage.CorpseSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Server-side owner of the three player states and every transition between them.
 * <ul>
 * <li>ALIVE: not in the registry and not dying.</li>
 * <li>DOWNED: in the registry. Memory only on purpose: a downed flag is never written to disk, so a crash or a restart
 * can never leave a player blocked (see {@link #handleLogout}).</li>
 * <li>DEAD: {@code isDeadOrDying()}; vanilla death (BlockZ / Curios / keepInventory rules apply unchanged).</li>
 * </ul>
 * All methods must run on the logical server thread.
 */
public final class DownedManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum PlayerState { ALIVE, DOWNED, DEAD }

    /** Transient movement-speed modifier that makes a downed player crawl (removed by every exit from the state). */
    private static final UUID CRAWL_MODIFIER_ID = UUID.fromString("5c3d6f0e-9a41-4b7b-8f6e-2d1a7c9e4b10");

    private static final DownedRegistry REGISTRY = new DownedRegistry();
    /** Players being finished off right now: the downed interception of {@code LivingDeathEvent} must let this death through. */
    private static final Set<UUID> FINISHING = new HashSet<>();
    /** Short invulnerability after a revive: uuid -> game tick when it ends. */
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();
    /** Players that were killed on logout while downed and must be respawned when they come back. */
    private static final Set<UUID> AWAITING_RESPAWN = new HashSet<>();

    private DownedManager() { }

    public static DownedRegistry registry() { return REGISTRY; }

    public static boolean isDowned(UUID id) { return REGISTRY.isDowned(id); }

    public static boolean isProtected(UUID id) { return PROTECTED.containsKey(id); }

    public static boolean isFinishing(UUID id) { return FINISHING.contains(id); }

    // ---- crawling (speed modifier) ----------------------------------------------------------------------------------

    private static void applyCrawl(ServerPlayer p) {
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getModifier(CRAWL_MODIFIER_ID) != null) return;
        double keep = Math.max(0.0D, Math.min(1.0D, DownedConfig.CRAWL_SPEED_PERCENT.get() / 100.0D));
        // MULTIPLY_TOTAL with -(1 - keep): the final speed is exactly {@code keep} times the normal one.
        speed.addTransientModifier(new AttributeModifier(CRAWL_MODIFIER_ID, "persistentbodies downed crawl",
                -(1.0D - keep), AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static void clearCrawl(ServerPlayer p) {
        AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(CRAWL_MODIFIER_ID);
    }

    public static PlayerState getState(ServerPlayer player) {
        if (player.isDeadOrDying()) return PlayerState.DEAD;
        return REGISTRY.isDowned(player.getUUID()) ? PlayerState.DOWNED : PlayerState.ALIVE;
    }

    private static long now(ServerPlayer p) { return p.getServer().getTickCount(); }

    // ---- ALIVE -> DOWNED -------------------------------------------------------------------------------------------

    /** @return true when the player was put in the downed state (the caller must then cancel the death). */
    public static boolean tryDown(ServerPlayer p, DamageSource source) {
        if (!DownedConfig.ENABLED.get() || p.isSpectator() || p.isCreative()) return false;
        if (!REGISTRY.down(p.getUUID(), now(p))) return false; // already downed: no second transition
        PROTECTED.remove(p.getUUID());
        p.stopRiding();
        p.stopUsingItem();
        p.closeContainer();
        p.setSprinting(false);
        p.clearFire();
        p.setAirSupply(p.getMaxAirSupply());
        p.setHealth(Math.min((float) (double) DownedConfig.DOWNED_HEALTH.get(), p.getMaxHealth()));
        applyCrawl(p);
        p.setPose(Pose.SWIMMING);
        syncAll(p.getServer());
        if (DownedConfig.ANNOUNCE.get()) {
            p.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.persistentbodies.downed", p.getDisplayName()), false);
        }
        p.displayClientMessage(Component.translatable("message.persistentbodies.you_are_downed"), true);
        LOGGER.info("{} is now downed ({}).", p.getGameProfile().getName(), source.getMsgId());
        return true;
    }

    // ---- per-tick upkeep of a downed player -------------------------------------------------------------------------

    public static void tickPlayer(ServerPlayer p) {
        if (!REGISTRY.isDowned(p.getUUID())) return;
        if (p.isDeadOrDying()) { onDeath(p); return; }
        p.setPose(Pose.SWIMMING); // synced to every client by vanilla entity data: the player lies flat
        p.setSprinting(false);
        if (p.isPassenger()) p.stopRiding();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        if (p.isUsingItem()) p.stopUsingItem();
        p.clearFire();
        if (p.getAirSupply() < p.getMaxAirSupply()) p.setAirSupply(p.getMaxAirSupply());

        // A downed player may only crawl: speed is cut by a transient attribute modifier (synced to the client, so no
        // rubber-banding). Jumping and sneaking are blocked on the client; here we just keep the state consistent.
        applyCrawl(p);

        int limit = DownedConfig.BLEED_OUT_SECONDS.get();
        if (limit > 0 && now(p) - REGISTRY.get(p.getUUID()).sinceTick >= limit * 20L) {
            LOGGER.info("{} bled out.", p.getGameProfile().getName());
            killDowned(p);
        }
    }

    /** Remaining ticks until bleed-out, or -1 when there is no time limit. */
    private static int ticksLeft(MinecraftServer server, UUID id) {
        int limit = DownedConfig.BLEED_OUT_SECONDS.get();
        DownedRegistry.Downed d = REGISTRY.get(id);
        if (limit <= 0 || d == null) return -1;
        return (int) Math.max(0L, limit * 20L - (server.getTickCount() - d.sinceTick));
    }

    // ---- DOWNED -> ALIVE ---------------------------------------------------------------------------------------------

    /**
     * Completes a revive: leaves the downed state and sets health to exactly {@link DownedConfig#REVIVE_HEALTH}.
     * Idempotent: only the first call on a downed player does anything. The registry entry is removed BEFORE touching
     * health, so no downed-only rule (heal cancel, damage cancel) can interfere and no death logic can run.
     */
    public static boolean revive(ServerPlayer p) {
        if (p.isDeadOrDying() || !REGISTRY.isDowned(p.getUUID())) return false;
        UUID id = p.getUUID();
        REGISTRY.remove(id);
        clearCrawl(p);
        p.setPose(Pose.STANDING);
        p.setHealth(Math.min(DownedConfig.REVIVE_HEALTH, p.getMaxHealth()));
        p.fallDistance = 0.0F;
        p.clearFire();
        p.setDeltaMovement(Vec3.ZERO);
        int shield = DownedConfig.REVIVE_INVULNERABILITY_TICKS.get();
        if (shield > 0) PROTECTED.put(id, now(p) + shield);
        syncAll(p.getServer());
        if (DownedConfig.ANNOUNCE.get()) {
            p.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.persistentbodies.revived", p.getDisplayName()), false);
        }
        LOGGER.info("{} was revived (health={}).", p.getGameProfile().getName(), p.getHealth());
        return true;
    }

    // ---- DOWNED -> DEAD ----------------------------------------------------------------------------------------------

    /**
     * Completes a durable offline-corpse death. The explicit FINISHING guard is important: custom or modded generic
     * damage sources are not guaranteed to carry the vanilla bypass tag, and must never turn this required death into
     * another downed state.
     */
    public static boolean killForPendingOfflineDeath(ServerPlayer p) {
        if (p.isDeadOrDying()) return true;
        UUID id = p.getUUID();
        boolean addedGuard = FINISHING.add(id);
        try {
            if (PlayerReviveBridge.isLoaded()) {
                if (!PlayerReviveBridge.kill(p)) return false;
                if (!p.isDeadOrDying()) {
                    LOGGER.error("PlayerRevive reported success without killing {}.", p.getGameProfile().getName());
                    return false;
                }
                return true;
            }
            p.kill();
            if (!p.isDeadOrDying()) {
                p.setHealth(0.0F);
                p.die(p.damageSources().genericKill());
            }
            if (!p.isDeadOrDying()) {
                LOGGER.error("Forced offline death did not kill {}.", p.getGameProfile().getName());
                return false;
            }
            return true;
        } finally {
            if (addedGuard) FINISHING.remove(id);
        }
    }

    /** Real death of a downed player (bleed-out, logout). Bypass damage can never put the player down again. */
    public static void killDowned(ServerPlayer p) {
        UUID id = p.getUUID();
        ReviveManager.cancelInvolving(p.getServer(), id, "message.persistentbodies.revive_cancelled_target_died");
        REGISTRY.remove(id); // from here on the player is not downed, nothing blocks the death
        clearCrawl(p);
        PROTECTED.remove(id);
        if (!p.isDeadOrDying()) {
            p.hurt(p.damageSources().genericKill(), Float.MAX_VALUE);
            if (!p.isDeadOrDying()) { // something cancelled the damage: ask for the death directly
                LOGGER.warn("Kill damage on {} was cancelled; calling die() directly.", p.getGameProfile().getName());
                p.die(p.damageSources().genericKill());
            }
        }
        syncAll(p.getServer());
    }

    /**
     * A player finishes off a downed one (left click). The victim really dies, credited to the attacker, through the normal
     * death path (death message, BlockZ / Curios / keepInventory rules). A totem cannot save them: the downed state already
     * is the "second chance". Idempotent: only the first call on a downed player does anything.
     *
     * @return true when the downed player was killed
     */
    public static boolean finish(ServerPlayer victim, ServerPlayer attacker) {
        UUID id = victim.getUUID();
        if (victim.isDeadOrDying() || !REGISTRY.isDowned(id)) return false;
        ReviveManager.cancelInvolving(victim.getServer(), id, "message.persistentbodies.revive_cancelled_target_died");
        REGISTRY.remove(id); // from here on the death is not intercepted by tryDown
        clearCrawl(victim);
        PROTECTED.remove(id);
        FINISHING.add(id);
        try {
            DamageSource source = attacker.damageSources().playerAttack(attacker);
            victim.setHealth(0.0F);
            victim.die(source);
            if (!victim.isDeadOrDying()) { // another mod brought them back: fall back to plain kill damage
                victim.setHealth(victim.getMaxHealth());
                victim.hurt(victim.damageSources().genericKill(), Float.MAX_VALUE);
            }
        } finally {
            FINISHING.remove(id);
        }
        LOGGER.info("{} finished off downed {}.", attacker.getGameProfile().getName(), victim.getGameProfile().getName());
        syncAll(victim.getServer());
        return true;
    }

    /** The player died by any route (including /kill and the void): forget every piece of downed state. */
    public static void onDeath(ServerPlayer p) {
        UUID id = p.getUUID();
        boolean was = REGISTRY.isDowned(id);
        ReviveManager.cancelInvolving(p.getServer(), id, "message.persistentbodies.revive_cancelled_target_died");
        REGISTRY.remove(id);
        clearCrawl(p);
        PROTECTED.remove(id);
        if (was) syncAll(p.getServer());
    }

    /** Defensive cleanup on respawn: a fresh player entity must never carry downed state. */
    public static void forget(ServerPlayer p) {
        onDeath(p);
    }

    // ---- logout / login ----------------------------------------------------------------------------------------------

    /**
     * Called from the logout handler BEFORE the body logic. A downed player who disconnects dies for real, exactly as if
     * they had died while connected (vanilla death: BlockZ corpse / Curios drops / keepInventory all apply), and NO
     * PlayerCorpseEntity is created. Works without the client: nothing here sends a packet the player must receive.
     * Idempotent: the registry entry is removed first, so a second call returns false and does nothing.
     *
     * @return true when the player was downed (the caller must skip the normal body creation)
     */
    public static boolean handleLogout(ServerPlayer p) {
        UUID id = p.getUUID();
        MinecraftServer server = p.getServer();
        ReviveManager.cancelInvolving(server, id, "message.persistentbodies.revive_cancelled_left");
        PROTECTED.remove(id);
        AWAITING_RESPAWN.remove(id);
        if (!REGISTRY.isDowned(id)) return false;
        LOGGER.info("{} disconnected while downed: finishing the death.", p.getGameProfile().getName());
        killDowned(p);
        if (p.isDeadOrDying()) {
            // The saved player file will contain a dead player. Remember to respawn them when they come back, so they
            // are not treated as downed and do not hang on a death screen that was never sent.
            CorpseSavedData data = CorpseSavedData.get(p.serverLevel());
            data.markRespawnOnLogin(id);
            CorpseSavedData.flush(p.serverLevel());
        } else {
            LOGGER.error("The death of downed player {} could not be completed on logout.", p.getGameProfile().getName());
        }
        return true;
    }

    /** Called from the login handler: sends the downed list and schedules the respawn of a player killed on logout. */
    public static void onLogin(ServerPlayer p) {
        REGISTRY.remove(p.getUUID()); // cannot be downed on login; cancels any stale session
        clearCrawl(p);
        syncTo(p);
        CorpseSavedData data = CorpseSavedData.get(p.serverLevel());
        if (data.needsRespawn(p.getUUID())) AWAITING_RESPAWN.add(p.getUUID());
    }

    /** Server tick: respawns players that were killed on logout while downed. */
    public static void tickLogin(MinecraftServer server) {
        PROTECTED.values().removeIf(end -> end <= server.getTickCount());
        if (AWAITING_RESPAWN.isEmpty()) return;
        for (UUID id : new ArrayList<>(AWAITING_RESPAWN)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { AWAITING_RESPAWN.remove(id); continue; } // left again: the marker stays on disk
            if (p.tickCount < 20) continue; // let the client finish joining
            AWAITING_RESPAWN.remove(id);
            CorpseSavedData data = CorpseSavedData.get(p.serverLevel());
            try {
                if (p.isDeadOrDying()) {
                    server.getPlayerList().respawn(p, false);
                    LOGGER.info("{} was respawned after dying while downed.", p.getGameProfile().getName());
                }
                data.clearRespawn(id);
                CorpseSavedData.flush(server.overworld());
            } catch (RuntimeException e) {
                LOGGER.error("Could not respawn {}; the marker is kept.", p.getGameProfile().getName(), e);
            }
        }
    }

    /** Server stopped (all players were already disconnected and handled): static state must not leak into the next world. */
    public static void clearAll() {
        REGISTRY.clear();
        PROTECTED.clear();
        FINISHING.clear();
        AWAITING_RESPAWN.clear();
    }

    // ---- synchronisation (presentation only) -------------------------------------------------------------------------

    private static DownedNetwork.DownedSyncPacket buildSync(MinecraftServer server) {
        List<DownedNetwork.DownedSyncPacket.Entry> entries = new ArrayList<>();
        for (UUID id : REGISTRY.allDowned())
            entries.add(new DownedNetwork.DownedSyncPacket.Entry(id, ticksLeft(server, id)));
        return new DownedNetwork.DownedSyncPacket(entries);
    }

    public static void syncAll(MinecraftServer server) {
        DownedNetwork.sendToAll(buildSync(server));
    }

    public static void syncTo(ServerPlayer p) {
        DownedNetwork.sendTo(p, buildSync(p.getServer()));
    }
}
