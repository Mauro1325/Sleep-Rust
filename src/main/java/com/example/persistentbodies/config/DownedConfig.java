package com.example.persistentbodies.config;

import com.example.persistentbodies.logic.ReviveCostRule;
import net.minecraftforge.common.ForgeConfigSpec;

/** Settings of the downed / revive system (file: persistentbodies-downed.toml, per world on the server). */
public final class DownedConfig {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue DOWNED_HEALTH;
    public static final ForgeConfigSpec.IntValue REVIVE_SECONDS;
    public static final ForgeConfigSpec.IntValue CRAWL_SPEED_PERCENT;
    public static final ForgeConfigSpec.BooleanValue ALLOW_FINISH_OFF;
    public static final ForgeConfigSpec.DoubleValue REVIVE_DISTANCE;
    public static final ForgeConfigSpec.EnumValue<ReviveCostRule.Mode> COST_MODE;
    public static final ForgeConfigSpec.IntValue HUNGER_COST;
    public static final ForgeConfigSpec.DoubleValue SATURATION_COST;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_REVIVER_FREE;
    public static final ForgeConfigSpec.IntValue BLEED_OUT_SECONDS;
    public static final ForgeConfigSpec.BooleanValue CANCEL_ON_REVIVER_DAMAGE;
    public static final ForgeConfigSpec.BooleanValue MOBS_IGNORE_DOWNED;
    public static final ForgeConfigSpec.IntValue REVIVE_INVULNERABILITY_TICKS;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE;
    public static final ForgeConfigSpec SPEC;
    /** Health a revived player gets: exactly 2 hearts (not configurable on purpose). */
    public static final float REVIVE_HEALTH = 4.0F;

    static {
        B.push("downed");
        ENABLED = B.comment("Lethal damage turns a player into a downed player instead of killing them.")
                .define("enabled", true);
        DOWNED_HEALTH = B.comment("Health points a player has while downed (must stay above 0).")
                .defineInRange("downedHealth", 1.0D, 0.5D, 20.0D);
        REVIVE_SECONDS = B.comment("Seconds the reviver must hold RIGHT CLICK while looking at the downed player's hitbox.",
                "Looking away for more than half a second cancels the revive. The old reviveDurationSeconds key is ignored.")
                .defineInRange("reviveHoldSeconds", 8, 1, 1800);
        CRAWL_SPEED_PERCENT = B.comment("Movement speed of a downed player as a percentage of normal (they crawl). 0 = cannot move.")
                .defineInRange("crawlSpeedPercent", 35, 0, 100);
        ALLOW_FINISH_OFF = B.comment("Another player can kill a downed player by hitting them (left click). Needs server PvP on.")
                .define("allowFinishOff", true);
        REVIVE_DISTANCE = B.comment("Maximum distance (blocks) between reviver and downed player, checked every tick.")
                .defineInRange("reviveDistance", 3.0D, 1.0D, 8.0D);
        COST_MODE = B.comment("What the reviver pays when the revive completes: HUNGER (food level points),",
                "SATURATION (saturation points) or BOTH. Saturation is spent before hunger in vanilla and is often 0.")
                .defineEnum("costMode", ReviveCostRule.Mode.HUNGER);
        HUNGER_COST = B.comment("Food level points (half drumsticks) charged in HUNGER / BOTH mode. 0 = free.")
                .defineInRange("hungerCost", 4, 0, 20);
        SATURATION_COST = B.comment("Saturation points charged in SATURATION / BOTH mode. 0 = free.")
                .defineInRange("saturationCost", 4.0D, 0.0D, 20.0D);
        CREATIVE_REVIVER_FREE = B.comment("Creative-mode revivers do not pay.")
                .define("creativeReviverIsFree", true);
        BLEED_OUT_SECONDS = B.comment("Seconds a player can remain downed before dying if nobody revives them.",
                "Default is 150 (2 min 30 s); 0 = never. The old bleedOutSeconds key is ignored.")
                .defineInRange("downedTimeoutSeconds", 150, 0, 86400);
        CANCEL_ON_REVIVER_DAMAGE = B.comment("Taking damage interrupts the reviver's action.")
                .define("cancelOnReviverDamage", true);
        MOBS_IGNORE_DOWNED = B.comment("Mobs stop targeting downed players.")
                .define("mobsIgnoreDowned", true);
        REVIVE_INVULNERABILITY_TICKS = B.comment("Invulnerability (ticks) right after being revived.")
                .defineInRange("reviveInvulnerabilityTicks", 40, 0, 600);
        ANNOUNCE = B.comment("Tell everyone in chat when a player is downed / revived.")
                .define("announce", true);
        B.pop();
        SPEC = B.build();
    }

    private DownedConfig() { }
}
