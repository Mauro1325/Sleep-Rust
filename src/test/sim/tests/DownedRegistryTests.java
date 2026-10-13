import java.util.UUID;
import com.example.persistentbodies.logic.DownedRegistry;
import com.example.persistentbodies.logic.DownedRegistry.StartResult;
public class DownedRegistryTests {
    static int pass, fail;
    static void check(String w, boolean ok) { System.out.println("    " + (ok ? "PASS" : "FAIL") + "  " + w); if (ok) pass++; else fail++; }
    public static void main(String[] x) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        DownedRegistry r = new DownedRegistry();

        check("down() is true the first time and false the second (no double transition)", r.down(b, 10) && !r.down(b, 11));
        check("sinceTick keeps the FIRST down tick", r.get(b).sinceTick == 10);
        check("a player cannot revive themself", r.start(b, b, 12) == StartResult.SELF);
        check("cannot revive a player that is not downed", r.start(a, c, 12) == StartResult.TARGET_NOT_DOWNED);
        check("alive reviver can start on a downed target", r.start(a, b, 12) == StartResult.OK);
        check("same reviver repeating the start only refreshes (heartbeat), still one session", r.start(a, b, 15) == StartResult.OK && r.sessions().size() == 1 && r.sessionOfReviver(a).lastHeartbeat == 15);
        check("second reviver on the same target is refused (one reviver per target)", r.start(c, b, 16) == StartResult.TARGET_BUSY);
        r.down(d, 20);
        check("reviver already busy cannot start another session", r.start(a, d, 21) == StartResult.REVIVER_BUSY);
        check("a downed player cannot revive", r.start(d, b, 21) == StartResult.REVIVER_DOWNED);

        DownedRegistry.Session s = r.sessionOfReviver(a);
        check("completion: endSession succeeds once", r.endSession(s));
        check("completion: a second endSession is a no-op (cost cannot be charged twice)", !r.endSession(s));
        check("after ending, target is free again", r.sessionOfTarget(b) == null && r.start(c, b, 30) == StartResult.OK);

        check("target logout/death: remove() cancels the session of its reviver", r.remove(b) && r.sessionOfReviver(c) == null && r.sessions().isEmpty());
        check("remove() is idempotent (logout + death handlers both running)", !r.remove(b));
        check("a removed player can be downed again later (new life)", r.down(b, 40));

        r.start(a, d, 50);
        check("reviver leaves: cancelInvolving drops the session and keeps the target downed", r.cancelInvolving(a) == 1 && r.isDowned(d) && r.sessions().isEmpty());
        check("cancelInvolving on an uninvolved player does nothing", r.cancelInvolving(UUID.randomUUID()) == 0);
        r.start(a, d, 60); r.heartbeat(a, d, 70);
        check("heartbeat updates the session only for the matching target", r.sessionOfReviver(a).lastHeartbeat == 70);
        r.heartbeat(a, b, 99);
        check("heartbeat for another target is ignored", r.sessionOfReviver(a).lastHeartbeat == 70);
        check("a new session starts with lastLook = start tick (look grace counts from the start)", r.sessionOfReviver(a).lastLook == 60);
        r.clear();
        check("clear() leaves nothing (server restart / reload)", r.allDowned().isEmpty() && r.sessions().isEmpty());
        System.out.println("\n==== " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
