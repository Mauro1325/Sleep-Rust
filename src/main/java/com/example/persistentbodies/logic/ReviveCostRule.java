package com.example.persistentbodies.logic;

/**
 * Deterministic revive cost rule (pure Java, no Minecraft types, unit-testable).
 *
 * Minecraft's FoodData has two independent numbers: foodLevel (int, 0..20, the drumsticks) and saturation
 * (float, 0..foodLevel, the hidden buffer that is spent BEFORE foodLevel drops). The rule used here is:
 * <ul>
 * <li>{@link Mode#HUNGER}: costs {@code hungerCost} points of foodLevel. Requires foodLevel &gt;= hungerCost.</li>
 * <li>{@link Mode#SATURATION}: costs {@code saturationCost} points of saturation. Requires saturation &gt;= saturationCost.
 * (Saturation is often 0 on a hungry player, so that player simply cannot pay.)</li>
 * <li>{@link Mode#BOTH}: both of the above, each one checked and charged independently.</li>
 * </ul>
 * A cost is never partially paid: either every required resource is available and all are charged, or nothing is
 * charged. Results are clamped to the legal FoodData range (foodLevel 0..20, 0 &lt;= saturation &lt;= foodLevel) and negative
 * configured costs count as 0.
 */
public final class ReviveCostRule {
    public enum Mode { HUNGER, SATURATION, BOTH }

    /** Outcome of a payment: the new values to store in FoodData. */
    public record Result(int foodLevel, float saturation) { }

    private ReviveCostRule() { }

    private static int hunger(int c) { return Math.max(0, c); }

    private static float sat(float c) { return Float.isNaN(c) ? 0.0F : Math.max(0.0F, c); }

    public static boolean canPay(Mode mode, int foodLevel, float saturation, int hungerCost, float saturationCost) {
        boolean needHunger = mode == Mode.HUNGER || mode == Mode.BOTH;
        boolean needSat = mode == Mode.SATURATION || mode == Mode.BOTH;
        if (needHunger && foodLevel < hunger(hungerCost)) return false;
        if (needSat && saturation < sat(saturationCost)) return false;
        return true;
    }

    /** @return the new food values, or null if the player cannot pay (nothing must be changed then). */
    public static Result pay(Mode mode, int foodLevel, float saturation, int hungerCost, float saturationCost) {
        if (!canPay(mode, foodLevel, saturation, hungerCost, saturationCost)) return null;
        int food = foodLevel;
        float satur = saturation;
        if (mode == Mode.HUNGER || mode == Mode.BOTH) food -= hunger(hungerCost);
        if (mode == Mode.SATURATION || mode == Mode.BOTH) satur -= sat(saturationCost);
        food = Math.max(0, Math.min(20, food));
        satur = Math.max(0.0F, Math.min(satur, food)); // saturation can never exceed foodLevel
        return new Result(food, satur);
    }
}
