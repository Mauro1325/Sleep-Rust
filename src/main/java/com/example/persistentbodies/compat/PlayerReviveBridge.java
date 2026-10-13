package com.example.persistentbodies.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/**
 * Optional PlayerRevive bridge (reflection only, nothing is needed at compile time).
 *
 * Verified against the PlayerRevive 2.0.31 sources (branch 1.20, mod_version=2.0.31):
 * <ul>
 * <li>{@code team.creative.playerrevive.server.PlayerReviveServer#kill(Player)} is {@code public static void}. It posts
 * PlayerBleedOutEvent, calls {@code setHealth(0)}, {@code forceBledOut()}, {@code player.die(source)} with the
 * {@code playerrevive:bled_to_death} source (which PlayerRevive's own LivingDeathEvent handler lets through), then
 * {@code resetPlayer(..)} and finally {@code revive.revive()}.</li>
 * <li>{@code PlayerReviveServer#getBleeding(Player)} returns {@code IBleeding}; {@code IBleeding} has
 * {@code isBleeding()}, {@code revive()} and {@code forceBledOut()}.</li>
 * </ul>
 *
 * Why there is NO "mark as bled out, then player.kill()" fallback any more: once {@code forceBledOut()} ran,
 * PlayerRevive's LivingHurtEvent handler cancels every hit ({@code if (revive.bledOut()) event.setCanceled(true)}), so
 * {@code player.kill()} does nothing, and the half-applied state (bleeding=true, timeLeft=0) makes PlayerRevive's own
 * player tick call {@code kill(player)} on every tick. If that call is what is failing, the exception would then be
 * thrown from inside PlayerRevive's tick handler. On failure this bridge therefore restores the previous state, logs the
 * real exception and reports "not dead" so the caller keeps the durable marker and retries later.
 *
 * "Dead" is never decided from health alone: {@code kill(Player)} calls {@code setHealth(0)} BEFORE {@code die(..)}, so a
 * failure inside {@code die(..)} leaves a player with 0 health. Success requires health &lt;= 0 AND PlayerRevive no longer
 * flagging the player as bleeding (its last statement is {@code revive.revive()}).
 */
public final class PlayerReviveBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "playerrevive";
    private static final String SERVER = "team.creative.playerrevive.server.PlayerReviveServer";

    private static boolean resolved;
    private static boolean available;
    private static boolean modLoaded;
    private static Method kill;
    private static Method getBleeding;
    private static Method isBleeding;
    private static Method revive;

    private PlayerReviveBridge() { }

    private static synchronized boolean resolve() {
        if (resolved) return available;
        resolved = true;
        modLoaded = ModList.get().isLoaded(MOD_ID);
        if (!modLoaded) return false;
        try {
            Class<?> server = Class.forName(SERVER);
            kill = server.getMethod("kill", Player.class);
            // Optional helpers (state inspection / rollback). The death itself only needs kill().
            try {
                getBleeding = server.getMethod("getBleeding", Player.class);
                isBleeding = getBleeding.getReturnType().getMethod("isBleeding");
                revive = getBleeding.getReturnType().getMethod("revive");
            } catch (NoSuchMethodException e) {
                getBleeding = null;
                isBleeding = null;
                revive = null;
                LOGGER.warn("PlayerRevive state API (getBleeding/isBleeding/revive) not found; death confirmation will rely on health only.", e);
            }
            available = true;
            LOGGER.info("PlayerRevive API resolved: kill={}, stateApi={}", kill, getBleeding != null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.error("PlayerRevive is installed but PlayerReviveServer#kill(Player) could not be resolved; offline deaths will stay pending.", e);
        }
        return available;
    }

    /** True if the mod is installed, even when its reflective API could not be resolved. */
    public static boolean isLoaded() {
        resolve();
        return modLoaded;
    }

    /** True when PlayerRevive currently flags this player as downed (bleeding). False when unknown. */
    public static boolean isBleeding(ServerPlayer player) {
        if (!resolve() || getBleeding == null || isBleeding == null) return false;
        try {
            Object state = getBleeding.invoke(null, player);
            return state != null && Boolean.TRUE.equals(isBleeding.invoke(state));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Could not read the PlayerRevive state of {}", player.getGameProfile().getName(), e);
            return false;
        }
    }

    /**
     * Kills the player for real, bypassing the "downed" state.
     *
     * @return true only when the death is confirmed (health &lt;= 0 and PlayerRevive state cleared). When false the
     *         player was left in the state it had before the attempt and the caller must keep its durable marker and retry.
     *         Never issues an unguarded plain {@code player.kill()} while PlayerRevive is installed.
     */
    public static boolean kill(ServerPlayer player) {
        if (!isLoaded()) return false;
        String name = player.getGameProfile().getName();
        if (player.isDeadOrDying() && !isBleeding(player)) return true; // idempotent: already dead
        if (!resolve() || kill == null) {
            LOGGER.error("Cannot finish the death of {}: PlayerRevive is installed but its kill API is unavailable.", name);
            return false;
        }
        boolean wasBleeding = isBleeding(player);
        try {
            kill.invoke(null, player);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("PlayerReviveServer#kill threw for {} (health={}, bleeding={})", name, player.getHealth(), isBleeding(player), cause);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.error("PlayerReviveServer#kill could not be invoked for {}", name, e);
        }
        boolean stillBleeding = isBleeding(player);
        if (player.isDeadOrDying() && !stillBleeding) {
            LOGGER.info("PlayerRevive confirmed the death of {}", name);
            return true;
        }
        LOGGER.error("PlayerRevive did not complete the death of {} (health={}, bleeding={}). The pending-death marker is kept.",
                name, player.getHealth(), stillBleeding);
        rollbackHalfAppliedState(player, wasBleeding, stillBleeding);
        return false;
    }

    /**
     * kill() may fail after forceBledOut(). Leaving bleeding=true/timeLeft=0 would make PlayerRevive call kill() from its
     * own tick every tick. Only a state that did not exist before our attempt is rolled back, and only on a player that
     * is still alive; a player who was genuinely downed before stays downed.
     */
    private static void rollbackHalfAppliedState(ServerPlayer player, boolean wasBleeding, boolean stillBleeding) {
        if (wasBleeding || !stillBleeding || player.isDeadOrDying() || getBleeding == null || revive == null) return;
        try {
            Object state = getBleeding.invoke(null, player);
            if (state != null) revive.invoke(state);
            LOGGER.warn("Rolled back the half-applied PlayerRevive bleeding state of {}", player.getGameProfile().getName());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.error("Could not roll back the PlayerRevive state of {}", player.getGameProfile().getName(), e);
        }
    }
}
