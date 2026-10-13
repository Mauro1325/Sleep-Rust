package com.example.persistentbodies.logic;

/**
 * Retry schedule for a pending offline death. Pure Java (no Minecraft types) so it can be unit-tested on its own.
 *
 * The durable marker in SavedData is never dropped because of this class: it only decides WHEN the next attempt runs.
 * The delay doubles after each failure (1 s, 2 s, 4 s ... capped at 60 s) so a permanently failing death
 * (another mod cancelling it, say) cannot spam the log or run the death path every tick, and it never gives up.
 */
public final class DeathRetryPolicy {
    public static final int BASE_DELAY_TICKS = 20;
    public static final int MAX_DELAY_TICKS = 1200;

    private int failures;
    private long nextAttemptTick;

    /** Delay in ticks after {@code failures} consecutive failures (failures &gt;= 1). */
    public static int delayAfter(int failures) {
        if (failures <= 0) return 0;
        long delay = BASE_DELAY_TICKS;
        for (int i = 1; i < failures && delay < MAX_DELAY_TICKS; i++) delay <<= 1;
        return (int) Math.min(delay, MAX_DELAY_TICKS);
    }

    public boolean isDue(long nowTick) {
        return nowTick >= nextAttemptTick;
    }

    /** Records a failed attempt and returns the delay (in ticks) before the next one. */
    public int failed(long nowTick) {
        failures++;
        int delay = delayAfter(failures);
        nextAttemptTick = nowTick + delay;
        return delay;
    }

    public int failures() {
        return failures;
    }
}
