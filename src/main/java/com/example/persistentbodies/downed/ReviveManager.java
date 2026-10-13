package com.example.persistentbodies.downed;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.example.persistentbodies.config.DownedConfig;
import com.example.persistentbodies.logic.DownedRegistry;
import com.example.persistentbodies.logic.ReviveCostRule;
import com.example.persistentbodies.network.DownedNetwork;
import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Server-side revive sessions. A session exists only while the client keeps sending the "holding right click" heartbeat
 * AND every condition holds; everything is re-validated each server tick. The client never decides anything.
 *
 * Conditions every tick: both players online and in the same dimension, reviver alive and not downed, target downed,
 * distance, cost, and the reviver's crosshair on the target's hitbox (checked here with a ray, not trusted from the
 * client; a short grace absorbs lag / aim jitter).
 *
 * Completion order (single thread, one tick): end the session (so it can only complete once) -> re-check the cost ->
 * {@link DownedManager#revive} (idempotent) -> charge the cost. The cost is only charged after a revive really
 * happened, and cannot fail because it was verified in the same tick.
 */
public final class ReviveManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The client sends a heartbeat every 5 ticks; no heartbeat for this long = the key was released / packets lost. */
    private static final int HEARTBEAT_TIMEOUT_TICKS = 15;
    /** The reviver may lose the target's hitbox for this many ticks (0.5 s) before the revive is cancelled. */
    private static final int LOOK_GRACE_TICKS = 10;
    /** Extra reach for the look ray, on top of the configured distance (eye vs. centre differences, latency). */
    private static final double LOOK_REACH_MARGIN = 0.5D;
    /** Hitbox tolerance of the look ray (blocks) so a 1-tick network wobble does not cancel a long revive. */
    private static final double LOOK_BOX_MARGIN = 0.1D;
    private static final int ARM_SWING_EVERY = 8;
    private static final int PARTICLES_EVERY = 10;

    /** Revivers whose shift key this class forced down (kneeling animation); restored when their session is gone. */
    private static final Set<UUID> KNEELING = new HashSet<>();

    private ReviveManager() { }

    private static DownedRegistry reg() { return DownedManager.registry(); }

    private static int requiredTicks() { return DownedConfig.REVIVE_SECONDS.get() * 20; }

    /** True while this player is reviving someone (their right clicks must not use items / interact meanwhile). */
    public static boolean isReviving(UUID reviver) { return reg().sessionOfReviver(reviver) != null; }

    // ---- cost ----------------------------------------------------------------------------------------------------

    private static boolean isFree(ServerPlayer reviver) {
        return DownedConfig.CREATIVE_REVIVER_FREE.get() && reviver.isCreative();
    }

    public static boolean canPay(ServerPlayer reviver) {
        if (isFree(reviver)) return true;
        FoodData food = reviver.getFoodData();
        return ReviveCostRule.canPay(DownedConfig.COST_MODE.get(), food.getFoodLevel(), food.getSaturationLevel(),
                DownedConfig.HUNGER_COST.get(), (float) (double) DownedConfig.SATURATION_COST.get());
    }

    private static void pay(ServerPlayer reviver) {
        if (isFree(reviver)) return;
        FoodData food = reviver.getFoodData();
        ReviveCostRule.Result r = ReviveCostRule.pay(DownedConfig.COST_MODE.get(), food.getFoodLevel(),
                food.getSaturationLevel(), DownedConfig.HUNGER_COST.get(),
                (float) (double) DownedConfig.SATURATION_COST.get());
        if (r == null) { // cannot happen: canPay was checked in the same tick
            LOGGER.error("Revive cost of {} could not be charged.", reviver.getGameProfile().getName());
            return;
        }
        food.setFoodLevel(r.foodLevel());
        food.setSaturation(r.saturation());
    }

    // ---- look check (server-side ray) -------------------------------------------------------------------------------

    /** True when the reviver's view ray (eye, look direction) hits the target's hitbox with no block in between. */
    static boolean isLookingAt(ServerPlayer reviver, ServerPlayer target) {
        double reach = DownedConfig.REVIVE_DISTANCE.get() + LOOK_REACH_MARGIN;
        Vec3 eye = reviver.getEyePosition();
        Vec3 end = eye.add(reviver.getViewVector(1.0F).scale(reach));
        AABB box = target.getBoundingBox().inflate(LOOK_BOX_MARGIN);
        Vec3 point;
        if (box.contains(eye)) {
            point = eye;
        } else {
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) return false;
            point = hit.get();
        }
        BlockHitResult block = reviver.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, reviver));
        return block.getType() == HitResult.Type.MISS;
    }

    // ---- packet entry point (untrusted input) ----------------------------------------------------------------------

    public static void onHold(ServerPlayer reviver, UUID targetId, boolean holding) {
        if (!DownedConfig.ENABLED.get()) return;
        MinecraftServer server = reviver.getServer();
        if (!holding) {
            DownedRegistry.Session s = reg().sessionOfReviver(reviver.getUUID());
            if (s != null && s.target.equals(targetId)) cancel(server, s, null);
            return;
        }
        long now = server.getTickCount();
        DownedRegistry.Session existing = reg().sessionOfReviver(reviver.getUUID());
        if (existing != null && existing.target.equals(targetId)) {
            reg().heartbeat(reviver.getUUID(), targetId, now);
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetId); // must be online
        String error = checkConditions(reviver, target);
        if (error == null && !isLookingAt(reviver, target)) error = "message.persistentbodies.revive_not_looking";
        if (error != null) { reviver.displayClientMessage(Component.translatable(error), true); return; }
        switch (reg().start(reviver.getUUID(), targetId, now)) {
            case OK -> {
                target.displayClientMessage(Component.translatable("message.persistentbodies.being_revived",
                        reviver.getDisplayName()), true);
                send(reviver, targetId, 0.0F);
                send(target, targetId, 0.0F);
            }
            case SELF -> reviver.displayClientMessage(Component.translatable("message.persistentbodies.revive_self"), true);
            case TARGET_BUSY -> reviver.displayClientMessage(Component.translatable("message.persistentbodies.revive_busy"), true);
            case TARGET_NOT_DOWNED -> reviver.displayClientMessage(Component.translatable("message.persistentbodies.revive_not_downed"), true);
            case REVIVER_DOWNED -> { /* a downed player cannot revive: ignore silently */ }
            case REVIVER_BUSY -> { /* already reviving somebody else: ignore */ }
        }
    }

    /** @return a translation key describing why the revive cannot proceed, or null when everything holds. */
    private static String checkConditions(ServerPlayer reviver, ServerPlayer target) {
        if (reviver == null || !reviver.isAlive() || reviver.isSpectator()) return "message.persistentbodies.revive_cancelled_reviver";
        if (target == null) return "message.persistentbodies.revive_not_downed"; // offline or unknown
        if (reviver == target) return "message.persistentbodies.revive_self";
        if (!target.isAlive() || !DownedManager.isDowned(target.getUUID())) return "message.persistentbodies.revive_not_downed";
        if (DownedManager.isDowned(reviver.getUUID())) return "message.persistentbodies.revive_cancelled_reviver";
        if (reviver.level() != target.level()) return "message.persistentbodies.revive_too_far";
        double max = DownedConfig.REVIVE_DISTANCE.get();
        if (reviver.distanceToSqr(target) > max * max) return "message.persistentbodies.revive_too_far";
        if (!canPay(reviver)) return "message.persistentbodies.revive_no_food";
        return null;
    }

    // ---- tick ------------------------------------------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (reg().sessions().isEmpty()) {
            if (!KNEELING.isEmpty()) restoreKneeling(server);
            return;
        }
        long now = server.getTickCount();
        for (DownedRegistry.Session s : reg().sessions()) {
            ServerPlayer reviver = server.getPlayerList().getPlayer(s.reviver);
            ServerPlayer target = server.getPlayerList().getPlayer(s.target);
            String error = checkConditions(reviver, target);
            if (error == null && now - s.lastHeartbeat > HEARTBEAT_TIMEOUT_TICKS) error = ""; // released: silent cancel
            if (error == null) {
                if (isLookingAt(reviver, target)) s.lastLook = now;
                else if (now - s.lastLook > LOOK_GRACE_TICKS) error = "message.persistentbodies.revive_not_looking";
            }
            if (error != null) { cancel(server, s, error.isEmpty() ? null : error); continue; }
            long elapsed = now - s.startTick;
            int required = requiredTicks();
            if (elapsed >= required) { complete(server, s, reviver, target); continue; }
            animate(reviver, target, elapsed);
            if (elapsed % 2 == 0) {
                float progress = (float) elapsed / (float) required;
                send(reviver, s.target, progress);
                send(target, s.target, progress);
            }
        }
        restoreKneeling(server);
    }

    /** Kneeling (shift pose) + alternating arm swings + healing particles over the downed player, visible to everyone. */
    private static void animate(ServerPlayer reviver, ServerPlayer target, long elapsed) {
        reviver.setShiftKeyDown(true);
        KNEELING.add(reviver.getUUID());
        if (elapsed % ARM_SWING_EVERY == 0)
            reviver.swing((elapsed / ARM_SWING_EVERY) % 2 == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, true);
        if (elapsed % PARTICLES_EVERY == 0 && target.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX(), target.getY() + 0.5D, target.getZ(),
                    3, 0.3D, 0.15D, 0.3D, 0.0D);
            if (elapsed % (PARTICLES_EVERY * 4) == 0)
                level.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + 0.8D, target.getZ(),
                        1, 0.2D, 0.1D, 0.2D, 0.0D);
        }
    }

    /** Stops the kneeling pose of everybody whose session ended (any reason: complete, cancel, death, logout). */
    private static void restoreKneeling(MinecraftServer server) {
        if (KNEELING.isEmpty()) return;
        KNEELING.removeIf(id -> {
            if (reg().sessionOfReviver(id) != null) return false;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.setShiftKeyDown(false);
            return true;
        });
    }

    private static void complete(MinecraftServer server, DownedRegistry.Session s, ServerPlayer reviver, ServerPlayer target) {
        if (!reg().endSession(s)) return; // already completed or cancelled: never twice
        send(reviver, s.target, -1.0F);
        send(target, s.target, -1.0F);
        if (!canPay(reviver)) { // re-check at completion time (hunger may have dropped while holding)
            reviver.displayClientMessage(Component.translatable("message.persistentbodies.revive_no_food"), true);
            return;
        }
        if (!DownedManager.revive(target)) return; // target no longer downed: nothing happens, nothing is charged
        pay(reviver);
        if (target.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + 1.0D, target.getZ(),
                    12, 0.4D, 0.4D, 0.4D, 0.0D);
            level.playSound(null, target.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.2F);
        }
        reviver.displayClientMessage(Component.translatable("message.persistentbodies.revive_done", target.getDisplayName()), true);
        target.displayClientMessage(Component.translatable("message.persistentbodies.revived_by", reviver.getDisplayName()), true);
    }

    // ---- cancellation ----------------------------------------------------------------------------------------------

    private static void cancel(MinecraftServer server, DownedRegistry.Session s, String reasonKey) {
        if (!reg().endSession(s)) return;
        ServerPlayer reviver = server.getPlayerList().getPlayer(s.reviver);
        ServerPlayer target = server.getPlayerList().getPlayer(s.target);
        send(reviver, s.target, -1.0F);
        send(target, s.target, -1.0F);
        if (reviver != null && reasonKey != null) reviver.displayClientMessage(Component.translatable(reasonKey), true);
    }

    /** Cancels every session in which this player is reviver or target (death, logout, dimension change, damage). */
    public static void cancelInvolving(MinecraftServer server, UUID id, String reasonKey) {
        if (server == null) return;
        DownedRegistry.Session a = reg().sessionOfReviver(id);
        if (a != null) cancel(server, a, reasonKey);
        DownedRegistry.Session b = reg().sessionOfTarget(id);
        if (b != null) cancel(server, b, reasonKey);
    }

    private static void send(ServerPlayer player, UUID target, float progress) {
        if (player != null) DownedNetwork.sendTo(player, new DownedNetwork.ReviveProgressPacket(target, progress, requiredTicks()));
    }
}
