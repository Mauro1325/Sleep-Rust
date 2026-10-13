package com.example.persistentbodies.compat_old;

import java.lang.reflect.Method;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

/**
 * Optional PlayerRevive bridge (reflection only, nothing is needed at compile time).
 *
 * PlayerRevive listens to LivingDeathEvent at the highest priority and, instead of letting a player die, cancels the
 * event and leaves them "downed" waiting to be revived. That is what happened to a player whose body was killed while
 * they were offline: on login PPB called {@code player.kill()} and PlayerRevive turned it into a downed player.
 *
 * PlayerRevive has its own way of finishing a downed player for good: {@code PlayerReviveServer.kill(Player)}. It marks
 * the player as bled out and then dies through the normal death path, which PlayerRevive itself lets through. Using it
 * means no config of PlayerRevive has to be touched and no event ordering has to be fought.
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
    private static Method forceBledOut;

    private PlayerReviveBridge() { }

    private static synchronized boolean resolve() {
        if (resolved) return available;
        resolved = true;
        modLoaded = ModList.get().isLoaded(MOD_ID);
        if (!modLoaded) return false;
        try {
            Class<?> server = Class.forName(SERVER);
            kill = server.getMethod("kill", Player.class);
            try {
                getBleeding = server.getMethod("getBleeding", Player.class);
                forceBledOut = getBleeding.getReturnType().getMethod("forceBledOut");
            } catch (NoSuchMethodException e) {
                getBleeding = null;
                forceBledOut = null;
            }
            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("PlayerRevive is installed but its API could not be read; offline deaths may leave the player downed.", e);
        }
        return available;
    }

    /** True if the mod is installed, even when its reflective API could not be resolved. */
    public static boolean isLoaded() {
        resolve();
        return modLoaded;
    }

    /**
     * Kills the player for real, bypassing the "downed" state.
     *
     * @return true when the player is confirmed dead; false when PlayerRevive is installed but the player remains alive.
     *         The caller must retain the pending-death marker and retry instead of issuing an unguarded plain kill.
     */
    public static boolean kill(ServerPlayer player) {
        if (!isLoaded()) return false;
        // First use PlayerRevive's own final-death path. It should mark the player as bled out
        // and then let its own LivingDeathEvent proceed instead of cancelling it as a downing.
        if (resolve() && kill != null) {
            try {
                kill.invoke(null, player);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                LOGGER.warn("PlayerRevive#kill failed for {}; trying the bled-out fallback", player.getGameProfile().getName(), e);
            }
        }
        if (!player.isAlive()) return true;

        // API fallback: mark the revive capability as bled out BEFORE sending another death
        // through Forge's LivingDeathEvent. Doing this after player.kill() is too late because
        // PlayerRevive may cancel that first event and leave the player downed.
        markBledOut(player);
        if (player.isAlive()) {
            try {
                player.kill();
            } catch (LinkageError | RuntimeException e) {
                LOGGER.error("Could not finish offline death for {} while PlayerRevive is installed",
                        player.getGameProfile().getName(), e);
            }
        }
        if (player.isAlive()) {
            LOGGER.error("PlayerRevive did not allow the offline death of {} to complete. The death marker will be retained and retried.",
                    player.getGameProfile().getName());
            return false;
        }
        return true;
    }

    private static void markBledOut(ServerPlayer player) {
        if (getBleeding == null || forceBledOut == null) return;
        try {
            Object bleeding = getBleeding.invoke(null, player);
            if (bleeding != null) forceBledOut.invoke(bleeding);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Could not mark {} as bled out in PlayerRevive", player.getGameProfile().getName(), e);
        }
    }
}
