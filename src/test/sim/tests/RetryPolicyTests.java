import com.example.persistentbodies.logic.DeathRetryPolicy;
public class RetryPolicyTests {
    static int pass, fail;
    static void check(String w, boolean ok) { System.out.println("    " + (ok ? "PASS" : "FAIL") + "  " + w); if (ok) pass++; else fail++; }
    public static void main(String[] a) {
        int[] expected = {0, 20, 40, 80, 160, 320, 640, 1200, 1200};
        for (int i = 0; i < expected.length; i++) check("delayAfter(" + i + ") == " + expected[i] + " (got " + DeathRetryPolicy.delayAfter(i) + ")", DeathRetryPolicy.delayAfter(i) == expected[i]);
        check("delayAfter(10000) is capped at 1200", DeathRetryPolicy.delayAfter(10000) == 1200);
        DeathRetryPolicy p = new DeathRetryPolicy();
        check("fresh policy is due immediately", p.isDue(0));
        int d = p.failed(100);
        check("first failure at tick 100 -> delay 20, next at 120", d == 20 && !p.isDue(119) && p.isDue(120));
        p.failed(120); check("second failure -> not due 39 ticks later, due after 40", !p.isDue(159) && p.isDue(160));
        for (int i = 0; i < 1000; i++) d = p.failed(1000L * i);
        check("after 1000 failures it never gives up: still schedules every 1200 ticks (delay=" + d + ", failures=" + p.failures() + ")", d == 1200 && p.failures() == 1002);
        System.out.println("\n==== " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
