package net.minecraft.world.entity.player;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
/** Minimal stand-in for Player/LivingEntity: just the death/hurt pipeline that PlayerRevive hooks into. */
public class Player {
    /** Forge LivingDeathEvent (HIGHEST handler of PlayerRevive). true = event cancelled. */
    public static BiPredicate<Player, Object> DEATH_EVENT = (p, s) -> false;
    /** Forge LivingHurtEvent. true = event cancelled. */
    public static Predicate<Player> HURT_EVENT = p -> false;
    public float health = 20f;
    public Object capability;          // PlayerRevive's Bleeding capability, created lazily by the replica
    public int dieCalls, deaths;       // deaths = die() ran past the event (drops, stats, ...)
    public RuntimeException dieThrows; // simulates another mod blowing up inside die()
    public Runnable dieSideEffect;     // simulates another mod touching the player inside die()
    public float getHealth() { return health; }
    public void setHealth(float h) { health = h; }
    public boolean isDeadOrDying() { return health <= 0f; }
    public boolean isAlive() { return health > 0f; }
    public void die(Object source) {
        dieCalls++;
        if (dieSideEffect != null) dieSideEffect.run();
        if (dieThrows != null) throw dieThrows;
        if (DEATH_EVENT.test(this, source)) return; // cancelled -> no death processing
        deaths++;
    }
    /** LivingEntity#kill -> hurt(genericKill, MAX_VALUE): LivingHurtEvent first, then die(). */
    public void kill() {
        if (isDeadOrDying()) return;
        if (HURT_EVENT.test(this)) return;
        health = 0f;
        die("genericKill");
    }
}
