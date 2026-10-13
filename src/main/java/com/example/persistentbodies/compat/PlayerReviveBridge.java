package com.example.persistentbodies.compat;

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
    private static Method kill;
    private static Method getBleeding;
    private static Method forceBledOut;

    private PlayerReviveBridge() { }

    private static synchronized boolean resolve() {
        if (resolved) return available;
        resolved = true;
        try {
            if (!ModList.get().isLoaded(MOD_ID)) return false;
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

    public static boolean isLoaded() {
        return resolve();
    }

    /**
     * Kills the player for real, bypassing the "downed" state.
     *
     * @return true when the player is dead afterwards (or PlayerRevive is not involved at all, so a plain kill is enough
     *         for the caller to do); false when PlayerRevive is loaded but the player is somehow still alive
     */
    public static boolean kill(ServerPlayer player) {
        if (!resolve()) return false;
        try {
            kill.invoke(null, player);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("PlayerRevive#kill failed for {}", player.getGameProfile().getName(), e);
        }
        if (!player.isAlive()) return true;
        // Second attempt: mark the player as bled out ourselves, so PlayerRevive lets the next death through.
        markBledOut(player);
        player.kill();
        return !player.isAlive();
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
