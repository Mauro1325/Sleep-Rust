import com.example.persistentbodies.logic.ReviveCostRule;
import com.example.persistentbodies.logic.ReviveCostRule.Mode;
public class ReviveCostTests {
    static int pass, fail;
    static void check(String w, boolean ok) { System.out.println("    " + (ok ? "PASS" : "FAIL") + "  " + w); if (ok) pass++; else fail++; }
    public static void main(String[] a) {
        var r = ReviveCostRule.pay(Mode.HUNGER, 20, 5.0F, 4, 4.0F);
        check("HUNGER: 20 food -> 16, saturation 5 untouched", r != null && r.foodLevel() == 16 && r.saturation() == 5.0F);
        r = ReviveCostRule.pay(Mode.HUNGER, 4, 4.0F, 4, 4.0F);
        check("HUNGER: exactly the cost is enough, food ends at 0 and saturation is clamped to <= food", r != null && r.foodLevel() == 0 && r.saturation() == 0.0F);
        check("HUNGER: 3 food cannot pay 4", ReviveCostRule.pay(Mode.HUNGER, 3, 20.0F, 4, 4.0F) == null);
        r = ReviveCostRule.pay(Mode.SATURATION, 10, 6.0F, 4, 4.0F);
        check("SATURATION: 6.0 -> 2.0, food untouched", r != null && r.foodLevel() == 10 && r.saturation() == 2.0F);
        check("SATURATION: 0 saturation cannot pay (even with full food)", ReviveCostRule.pay(Mode.SATURATION, 20, 0.0F, 4, 4.0F) == null);
        r = ReviveCostRule.pay(Mode.BOTH, 12, 5.0F, 4, 4.0F);
        check("BOTH: food 12->8 and saturation 5->1", r != null && r.foodLevel() == 8 && r.saturation() == 1.0F);
        check("BOTH: enough food but not enough saturation -> nothing is charged", ReviveCostRule.pay(Mode.BOTH, 20, 3.0F, 4, 4.0F) == null);
        check("BOTH: enough saturation but not enough food -> nothing is charged", ReviveCostRule.pay(Mode.BOTH, 2, 2.0F, 4, 1.0F) == null);
        r = ReviveCostRule.pay(Mode.BOTH, 10, 10.0F, -5, -3.0F);
        check("negative configured costs count as 0 (never add food)", r != null && r.foodLevel() == 10 && r.saturation() == 10.0F);
        r = ReviveCostRule.pay(Mode.HUNGER, 20, 20.0F, 4, 0F);
        check("saturation never ends above food level", r != null && r.saturation() <= r.foodLevel());
        r = ReviveCostRule.pay(Mode.HUNGER, 25, 30.0F, 0, 0F);
        check("out-of-range inputs are clamped to 0..20", r != null && r.foodLevel() == 20 && r.saturation() == 20.0F);
        check("NaN saturation cost is treated as free, no invalid state", ReviveCostRule.pay(Mode.SATURATION, 5, 1.0F, 0, Float.NaN) != null);
        System.out.println("\n==== " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
