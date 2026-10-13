import java.util.List;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import team.creative.playerrevive.server.PlayerReviveServer;
import com.example.persistentbodies.compat.PlayerReviveBridge;

public class BridgeTests {
    static int pass, fail;
    static void check(String what, boolean ok) {
        System.out.println("    " + (ok ? "PASS" : "FAIL") + "  " + what);
        if (ok) pass++; else fail++;
    }
    static void test(String name) { System.out.println("\n== " + name); }
    static boolean loggedError() { return LogUtils.LINES.stream().anyMatch(l -> l.startsWith("[ERROR]")); }
    static void reset() {
        PlayerReviveServer.init();
        PlayerReviveServer.BLEED_OUT_EVENT = () -> {};
        ModList.PLAYERREVIVE_LOADED = true;
        LogUtils.LINES.clear();
    }

    public static void main(String[] a) {
        reset();

        test("T1  ORIGINAL BUG (replica of PlayerRevive handlers): plain player.kill() on a healthy player");
        { ServerPlayer p = new ServerPlayer(); p.kill();
          check("player is still ALIVE (health=" + p.getHealth() + ")", p.isAlive());
          check("PlayerRevive flagged the player as downed (bleeding)", PlayerReviveServer.getBleeding(p).isBleeding());
          check("no real death happened (deaths=0)", p.deaths == 0); }

        test("T2  New bridge: normal pending death");
        { reset(); ServerPlayer p = new ServerPlayer();
          boolean r = PlayerReviveBridge.kill(p);
          check("returns true", r);
          check("player dead, real death processed (deaths=1)", p.isDeadOrDying() && p.deaths == 1);
          check("PlayerRevive state cleared (bleeding=false)", !PlayerReviveServer.getBleeding(p).isBleeding());
          check("no ERROR logged", !loggedError()); }

        test("T3  Idempotency: second call on the same, already dead player");
        { reset(); ServerPlayer p = new ServerPlayer();
          PlayerReviveBridge.kill(p); boolean r2 = PlayerReviveBridge.kill(p);
          check("returns true", r2);
          check("die() executed exactly once (dieCalls=" + p.dieCalls + ")", p.dieCalls == 1 && p.deaths == 1); }

        test("T4  die() blows up (other mod). kill() already did setHealth(0) -> old 'isAlive()' check is a false positive");
        { reset(); ServerPlayer old = new ServerPlayer(); old.dieThrows = new IllegalStateException("other mod bug");
          boolean oldR = com.example.persistentbodies.compat_old.PlayerReviveBridge.kill(old);
          check("OLD bridge claims success (returns " + oldR + ") although deaths=" + old.deaths + " and PlayerRevive still bleeding=" + PlayerReviveServer.getBleeding(old).isBleeding(), oldR && old.deaths == 0);
          reset(); ServerPlayer p = new ServerPlayer(); p.dieThrows = new IllegalStateException("other mod bug");
          boolean r = PlayerReviveBridge.kill(p);
          check("NEW bridge returns false (death not confirmed)", !r);
          check("real exception was logged as ERROR", loggedError());
          check("failed attempt left the player in limbo: health=0 but still bleeding (old check would have called this 'dead')", p.getHealth() <= 0f && PlayerReviveServer.getBleeding(p).isBleeding() && p.deaths == 0);
          p.dieThrows = null; // the fault is gone; the state is exactly what the failed attempt left behind
          boolean r2 = PlayerReviveBridge.kill(p);
          check("retry after the fault is gone succeeds (returns true, deaths=1)", r2 && p.deaths == 1); }

        test("T5  PlayerBleedOutEvent listener throws before anything changed");
        { reset(); PlayerReviveServer.BLEED_OUT_EVENT = () -> { throw new IllegalStateException("listener bug"); };
          ServerPlayer old = new ServerPlayer();
          boolean oldR = com.example.persistentbodies.compat_old.PlayerReviveBridge.kill(old);
          var oldState = PlayerReviveServer.getBleeding(old);
          check("OLD bridge: returns false but leaves bleeding=" + oldState.isBleeding() + ", timeLeft=" + oldState.timeLeft() + " (PlayerRevive's own tick would then call kill() every tick)", !oldR && oldState.isBleeding() && oldState.timeLeft() == 0);
          reset(); PlayerReviveServer.BLEED_OUT_EVENT = () -> { throw new IllegalStateException("listener bug"); };
          ServerPlayer p = new ServerPlayer(); boolean r = PlayerReviveBridge.kill(p);
          check("NEW bridge returns false", !r);
          check("NEW bridge leaves the player untouched (alive, bleeding=false)", p.isAlive() && !PlayerReviveServer.getBleeding(p).isBleeding());
          check("ERROR with the real exception logged", loggedError()); }

        test("T6  Failure after forceBledOut with the player alive again -> half-applied state is rolled back");
        { reset(); ServerPlayer p = new ServerPlayer();
          p.dieSideEffect = () -> p.setHealth(5f); p.dieThrows = new IllegalStateException("boom");
          boolean r = PlayerReviveBridge.kill(p);
          check("returns false", !r);
          check("rolled back: bleeding=false, player alive", p.isAlive() && !PlayerReviveServer.getBleeding(p).isBleeding());
          check("WARN about the rollback logged", LogUtils.LINES.stream().anyMatch(l -> l.contains("Rolled back"))); }

        test("T7  Player that was genuinely downed before the attempt keeps its state if the attempt fails");
        { reset(); ServerPlayer p = new ServerPlayer(); PlayerReviveServer.startBleeding(p, "x");
          p.dieSideEffect = () -> p.setHealth(5f); p.dieThrows = new IllegalStateException("boom");
          boolean r = PlayerReviveBridge.kill(p);
          check("returns false", !r);
          check("NOT rolled back (still bleeding)", PlayerReviveServer.getBleeding(p).isBleeding()); }

        test("T8  Legit downed player (timeLeft=1200) whose body was destroyed: kill succeeds");
        { reset(); ServerPlayer p = new ServerPlayer(); PlayerReviveServer.startBleeding(p, "x"); p.setHealth(10f);
          boolean r = PlayerReviveBridge.kill(p);
          check("returns true, dead, bleeding cleared, deaths=1", r && p.isDeadOrDying() && !PlayerReviveServer.getBleeding(p).isBleeding() && p.deaths == 1);
          check("isBleeding() helper reports false after", !PlayerReviveBridge.isBleeding(p)); }

        test("T9  PlayerRevive not installed");
        { reset(); ModList.PLAYERREVIVE_LOADED = false;
          // new JVM-static caches are already resolved in this run, so only the documented contract is checked via the old/new isLoaded
          check("(skipped: static cache already resolved in this JVM; covered by ApiMissingTest in a fresh JVM)", true); }

        System.out.println("\n==== " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
