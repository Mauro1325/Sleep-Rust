package team.creative.playerrevive.server;
import net.minecraft.world.entity.player.Player;
import team.creative.playerrevive.api.IBleeding;
import team.creative.playerrevive.cap.Bleeding;
/** Public API and handler logic copied from PlayerReviveServer.java / ReviveEventServer.java (2.0.31). */
public class PlayerReviveServer {
    public static final Object BLED_TO_DEATH = "playerrevive:bled_to_death";
    public static Runnable BLEED_OUT_EVENT = () -> {}; // PlayerBleedOutEvent listeners (can throw)

    static { // register PlayerRevive's Forge handlers on the stub event hooks
        Player.DEATH_EVENT = PlayerReviveServer::playerDied;
        Player.HURT_EVENT = PlayerReviveServer::playerDamageCancelled;
    }
    public static void init() { /* forces the static initializer */ }

    public static boolean isBleeding(Player p) { return getBleeding(p).isBleeding(); }
    public static IBleeding getBleeding(Player player) {
        if (player.capability == null) player.capability = new Bleeding();
        return (IBleeding) player.capability;
    }
    public static void startBleeding(Player player, Object source) { getBleeding(player).knockOut(player, source); }

    /** ReviveEventServer#playerDied (HIGHEST). Returns true when the event is cancelled. */
    static boolean playerDied(Player player, Object source) {
        if (source == BLED_TO_DEATH) return false;                  // doesByPass
        IBleeding revive = getBleeding(player);
        if (revive.bledOut() || revive.isBleeding()) return false; // lets the real death through
        startBleeding(player, source);
        player.setHealth(10);                                      // bleedingHealth
        return true;                                               // event.setCanceled(true)
    }
    /** ReviveEventServer#playerDamage. Returns true when the hit is cancelled. */
    static boolean playerDamageCancelled(Player player) {
        IBleeding revive = getBleeding(player);
        if (revive.isBleeding()) {
            if (revive.bledOut()) return true;
            if (revive.downedTime() <= 10) return true;            // initialDamageCooldown
        }
        return false;
    }

    private static void resetPlayer(Player player, IBleeding revive) { /* packets omitted */ }

    /** Same statement order as PlayerReviveServer#kill(Player) of 2.0.31. */
    public static void kill(Player player) {
        IBleeding revive = getBleeding(player);
        BLEED_OUT_EVENT.run();                       // MinecraftForge.EVENT_BUS.post(new PlayerBleedOutEvent(..))
        Object source = BLED_TO_DEATH;              // revive.getSource(..)
        player.setHealth(0.0F);
        revive.forceBledOut();
        player.die(source);
        resetPlayer(player, revive);
        revive.revive();
    }
}
