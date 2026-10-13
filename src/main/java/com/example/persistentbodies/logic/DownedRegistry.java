package com.example.persistentbodies.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure-Java state machine for downed players and revive sessions (no Minecraft types, unit-testable).
 *
 * States of a player: ALIVE (not present here), DOWNED (present in {@code downed}); DEAD is decided by the game and
 * simply means "removed from this registry". Every transition is idempotent: doing it twice has no extra effect.
 * Invariants: a player cannot revive themself; a downed player cannot revive; a target has at most ONE reviver and a
 * reviver at most ONE target at any time.
 */
public final class DownedRegistry {
    public static final class Downed {
        public final UUID id;
        public final long sinceTick;
        Downed(UUID id, long sinceTick) { this.id = id; this.sinceTick = sinceTick; }
    }

    public static final class Session {
        public final UUID reviver;
        public final UUID target;
        public final long startTick;
        public long lastHeartbeat;
        /** Last tick at which the reviver's crosshair was on the target's hitbox (the revive needs that, with a short grace). */
        public long lastLook;
        Session(UUID reviver, UUID target, long startTick) {
            this.reviver = reviver; this.target = target; this.startTick = startTick; this.lastHeartbeat = startTick;
            this.lastLook = startTick;
        }
    }

    public enum StartResult { OK, SELF, TARGET_NOT_DOWNED, REVIVER_DOWNED, TARGET_BUSY, REVIVER_BUSY }

    private final Map<UUID, Downed> downed = new HashMap<>();
    private final Map<UUID, Session> byTarget = new HashMap<>();
    private final Map<UUID, Session> byReviver = new HashMap<>();

    /** @return true only the first time (the player was not downed yet). */
    public boolean down(UUID id, long tick) {
        if (downed.containsKey(id)) return false;
        downed.put(id, new Downed(id, tick));
        return true;
    }

    public boolean isDowned(UUID id) { return downed.containsKey(id); }

    public Downed get(UUID id) { return downed.get(id); }

    public List<UUID> allDowned() { return new ArrayList<>(downed.keySet()); }

    /** Leaves the downed state (revive, death, logout) and cancels every session involving the player. */
    public boolean remove(UUID id) {
        cancelInvolving(id);
        return downed.remove(id) != null;
    }

    public StartResult start(UUID reviver, UUID target, long tick) {
        if (reviver.equals(target)) return StartResult.SELF;
        if (!downed.containsKey(target)) return StartResult.TARGET_NOT_DOWNED;
        if (downed.containsKey(reviver)) return StartResult.REVIVER_DOWNED;
        Session existing = byReviver.get(reviver);
        if (existing != null && existing.target.equals(target)) { existing.lastHeartbeat = tick; return StartResult.OK; }
        if (existing != null) return StartResult.REVIVER_BUSY;
        if (byTarget.containsKey(target)) return StartResult.TARGET_BUSY;
        Session s = new Session(reviver, target, tick);
        byTarget.put(target, s);
        byReviver.put(reviver, s);
        return StartResult.OK;
    }

    public Session sessionOfReviver(UUID reviver) { return byReviver.get(reviver); }

    public Session sessionOfTarget(UUID target) { return byTarget.get(target); }

    public List<Session> sessions() { return new ArrayList<>(byReviver.values()); }

    public void heartbeat(UUID reviver, UUID target, long tick) {
        Session s = byReviver.get(reviver);
        if (s != null && s.target.equals(target)) s.lastHeartbeat = tick;
    }

    /** Ends a session. @return true if it was still registered (so a completion can only happen once). */
    public boolean endSession(Session s) {
        boolean was = byReviver.remove(s.reviver, s);
        byTarget.remove(s.target, s);
        return was;
    }

    /** Cancels every session where the player is reviver or target. @return how many were cancelled. */
    public int cancelInvolving(UUID id) {
        int n = 0;
        Session a = byReviver.get(id);
        if (a != null && endSession(a)) n++;
        Session b = byTarget.get(id);
        if (b != null && endSession(b)) n++;
        return n;
    }

    public void clear() { downed.clear(); byTarget.clear(); byReviver.clear(); }
}
