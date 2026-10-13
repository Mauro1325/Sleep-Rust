package com.example.persistentbodies.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class CorpseConfig {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue LIFETIME_MINUTES;
    public static final ForgeConfigSpec.BooleanValue ALLOW_LOOTING;
    public static final ForgeConfigSpec.BooleanValue RECOVER_ON_JOIN;
    public static final ForgeConfigSpec.BooleanValue REMOVE_EMPTY;
    public static final ForgeConfigSpec.BooleanValue MESSAGES;
    public static final ForgeConfigSpec.BooleanValue BLOCKZ_HANDOFF;
    public static final ForgeConfigSpec.BooleanValue SUPPRESS_LOGIN_DEATH_MESSAGE;
    public static final ForgeConfigSpec.IntValue MAX_EXTRA_SLOTS;
    public static final ForgeConfigSpec SPEC;
    static {
        B.push("general");
        ENABLED = B.define("enabled", true);
        LIFETIME_MINUTES = B.defineInRange("lifetimeMinutes", 0, 0, 525600);
        ALLOW_LOOTING = B.define("allowLooting", true);
        RECOVER_ON_JOIN = B.define("recoverOnJoin", true);
        REMOVE_EMPTY = B.define("removeEmptyBodies", true);
        MESSAGES = B.define("showMessages", true);
        BLOCKZ_HANDOFF = B.comment("When BlockZ is installed and its corpse system is enabled, a body killed while its owner is",
                "offline turns into a BlockZ corpse (with every item) instead of scattering its items on the ground.")
                .define("blockzHandoff", true);
        SUPPRESS_LOGIN_DEATH_MESSAGE = B.comment("The owner of a body killed while offline dies again when they log in. Hide that second",
                "death message (the kill was already announced when the body died).")
                .define("suppressLoginDeathMessage", true);
        MAX_EXTRA_SLOTS = B.comment("Maximum number of extra slots (Curios + BlockZ equipment) a body can hold. Slots beyond",
                "this limit are not moved to the body and stay with the player.")
                .defineInRange("maxExtraSlots", 64, 0, 64);
        B.pop(); SPEC = B.build();
    }
    private CorpseConfig() { }
}
