package com.example.persistentbodies.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent owner UUID -> corpse record index, stored in the overworld data storage.
 *
 * Besides the corpse UUID, each record remembers WHERE the body is (dimension + coordinates) so the owner can be
 * brought to it on login even if the body's chunk is unloaded, and a backup snapshot of the body state (items,
 * health, totem effects) that is kept in sync with the entity. The snapshot is only used if the entity cannot be
 * found at all (deleted, chunk data lost, ...).
 */
public class CorpseSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "persistentbodies_corpses";

    /** Everything known about one offline player's body. */
    public static final class Record {
        public final UUID corpse;
        public boolean hasLocation;
        public String dimension = "";
        public double x;
        public double y;
        public double z;
        public float yaw;
        /** Nullable. Contains "Items", "Health", "MaxHealth", "Regen", "Fire", "AbsTicks", "Abs". */
        @Nullable
        public CompoundTag snapshot;

        Record(UUID corpse) {
            this.corpse = corpse;
        }
    }

    private final Map<UUID, Record> records = new HashMap<>();
    /** Owners whose body was killed while they were offline: they must die on their next login. */
    private final Set<UUID> dead = new HashSet<>();
    /** Name of whoever killed the body of an owner in {@link #dead} (absent when unknown), shown on their death screen. */
    private final Map<UUID, String> deadKiller = new HashMap<>();
    /** Damage type that killed the body, used when there was no player/entity attacker. */
    private final Map<UUID, String> deadCause = new HashMap<>();
    /**
     * Corpse entities whose contents were already handed to the player from the snapshot. If such an entity ever
     * shows up again (late chunk load) it must delete itself WITHOUT dropping anything, or items would be duplicated.
     */
    private final Set<UUID> orphans = new HashSet<>();
    /**
     * Players who disconnected while DOWNED and were killed on logout. Their saved file holds a dead player, so they
     * are respawned when they log in again (they are never treated as downed). Optional tag: old worlds just lack it.
     */
    private final Set<UUID> respawnOnLogin = new HashSet<>();

    public static CorpseSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(CorpseSavedData::load,
                CorpseSavedData::new, NAME);
    }

    public static CorpseSavedData load(CompoundTag tag) {
        CorpseSavedData data = new CorpseSavedData();
        // Legacy format (owner -> corpse UUID only).
        CompoundTag legacy = tag.getCompound("Pending");
        for (String key : legacy.getAllKeys()) {
            try {
                data.records.put(UUID.fromString(key), new Record(UUID.fromString(legacy.getString(key))));
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag recs = tag.getCompound("Records");
        for (String key : recs.getAllKeys()) {
            try {
                CompoundTag t = recs.getCompound(key);
                Record r = new Record(UUID.fromString(t.getString("Corpse")));
                r.hasLocation = t.getBoolean("HasLocation");
                r.dimension = t.getString("Dim");
                r.x = t.getDouble("X");
                r.y = t.getDouble("Y");
                r.z = t.getDouble("Z");
                r.yaw = t.getFloat("Yaw");
                if (t.contains("Snapshot", Tag.TAG_COMPOUND))
                    r.snapshot = t.getCompound("Snapshot");
                data.records.put(UUID.fromString(key), r);
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String key : tag.getCompound("Dead").getAllKeys()) {
            try {
                data.dead.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag killers = tag.getCompound("DeadKiller");
        for (String key : killers.getAllKeys()) {
            try {
                data.deadKiller.put(UUID.fromString(key), killers.getString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        CompoundTag causes = tag.getCompound("DeadCause");
        for (String key : causes.getAllKeys()) {
            try {
                data.deadCause.put(UUID.fromString(key), causes.getString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String key : tag.getCompound("Orphans").getAllKeys()) {
            try {
                data.orphans.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String key : tag.getCompound("DownedLogout").getAllKeys()) {
            try {
                data.respawnOnLogin.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag recs = new CompoundTag();
        for (Map.Entry<UUID, Record> entry : records.entrySet()) {
            Record r = entry.getValue();
            CompoundTag t = new CompoundTag();
            t.putString("Corpse", r.corpse.toString());
            t.putBoolean("HasLocation", r.hasLocation);
            t.putString("Dim", r.dimension);
            t.putDouble("X", r.x);
            t.putDouble("Y", r.y);
            t.putDouble("Z", r.z);
            t.putFloat("Yaw", r.yaw);
            if (r.snapshot != null)
                t.put("Snapshot", r.snapshot);
            recs.put(entry.getKey().toString(), t);
        }
        tag.put("Records", recs);
        CompoundTag deadTag = new CompoundTag();
        for (UUID owner : dead)
            deadTag.putBoolean(owner.toString(), true);
        tag.put("Dead", deadTag);
        CompoundTag killerTag = new CompoundTag();
        for (Map.Entry<UUID, String> entry : deadKiller.entrySet())
            killerTag.putString(entry.getKey().toString(), entry.getValue());
        tag.put("DeadKiller", killerTag);
        CompoundTag causeTag = new CompoundTag();
        for (Map.Entry<UUID, String> entry : deadCause.entrySet())
            causeTag.putString(entry.getKey().toString(), entry.getValue());
        tag.put("DeadCause", causeTag);
        CompoundTag orphanTag = new CompoundTag();
        for (UUID corpse : orphans)
            orphanTag.putBoolean(corpse.toString(), true);
        tag.put("Orphans", orphanTag);
        CompoundTag logoutTag = new CompoundTag();
        for (UUID owner : respawnOnLogin)
            logoutTag.putBoolean(owner.toString(), true);
        tag.put("DownedLogout", logoutTag);
        return tag;
    }

    // ---- Records
    // -----------------------------------------------------------------------------------

    public void register(UUID owner, UUID corpse, ResourceKey<Level> dimension, double x, double y, double z,
            float yaw) {
        Record r = new Record(corpse);
        r.hasLocation = true;
        r.dimension = dimension.location().toString();
        r.x = x;
        r.y = y;
        r.z = z;
        r.yaw = yaw;
        records.put(owner, r);
        setDirty();
    }

    /** Corpse UUID registered for this owner, or null. */
    @Nullable
    public UUID get(UUID owner) {
        Record r = records.get(owner);
        return r == null ? null : r.corpse;
    }

    @Nullable
    public Record getRecord(UUID owner) {
        return records.get(owner);
    }

    public void remove(UUID owner) {
        records.remove(owner);
        setDirty();
    }

    /** Called by the body itself when it moves. Ignored if the record belongs to another entity. */
    public void updateLocation(UUID owner, UUID corpse, ResourceKey<Level> dimension, double x, double y, double z,
            float yaw) {
        Record r = records.get(owner);
        if (r == null || !r.corpse.equals(corpse))
            return;
        String dim = dimension.location().toString();
        if (r.hasLocation && r.dimension.equals(dim) && Math.abs(r.x - x) < 0.25D && Math.abs(r.y - y) < 0.25D
                && Math.abs(r.z - z) < 0.25D)
            return;
        r.hasLocation = true;
        r.dimension = dim;
        r.x = x;
        r.y = y;
        r.z = z;
        r.yaw = yaw;
        setDirty();
    }

    /** Called by the body whenever its items/health change. Ignored if the record belongs to another entity. */
    public void updateSnapshot(UUID owner, UUID corpse, CompoundTag snapshot) {
        Record r = records.get(owner);
        if (r == null || !r.corpse.equals(corpse))
            return;
        r.snapshot = snapshot;
        setDirty();
    }

    // ---- Offline death
    // -----------------------------------------------------------------------------------

    public void markDead(UUID owner) {
        dead.add(owner);
        setDirty();
    }

    /** Same as {@link #markDead(UUID)}, remembering who killed the body (null or empty when nobody did). */
    public void markDead(UUID owner, String killer) {
        if (killer != null && !killer.isEmpty())
            deadKiller.put(owner, killer);
        markDead(owner);
    }

    /** Marks an offline death and persists either its attacker or its damage type for the later death screen. */
    public void markDead(UUID owner, String killer, String cause) {
        if (cause != null && !cause.isEmpty())
            deadCause.put(owner, cause);
        markDead(owner, killer);
    }

    /** The killer remembered for this owner (removed once read), or an empty string. */
    public String takeKiller(UUID owner) {
        String killer = deadKiller.remove(owner);
        if (killer != null)
            setDirty();
        return killer == null ? "" : killer;
    }

    /** True if this owner's body died while they were offline. Does not consume the durable marker. */
    public boolean isDeadPending(UUID owner) {
        return dead.contains(owner);
    }

    /** Snapshot copy of every owner with a pending death (safe to iterate while the set changes). */
    public Set<UUID> pendingDeadOwners() {
        return dead.isEmpty() ? Set.of() : new HashSet<>(dead);
    }

    /**
     * Writes every dirty SavedData of the server to disk right now instead of waiting for the next autosave.
     * Used right after the pending-death marker is created and right after it is resolved, so a crash cannot leave a
     * marker that was already processed (a second, undeserved death at the next login) or lose one that was just
     * registered. This is a plain file write (survives a killed process, not a power cut).
     */
    public static void flush(ServerLevel level) {
        try {
            level.getServer().overworld().getDataStorage().save();
        } catch (RuntimeException e) {
            LOGGER.error("Could not flush persistentbodies SavedData to disk", e);
        }
    }

    /** The killer remembered for this pending death without removing it. */
    public String getDeadKiller(UUID owner) {
        String killer = deadKiller.get(owner);
        return killer == null ? "" : killer;
    }

    /** Damage type that caused this pending death, or an empty string when unknown. */
    public String getDeadCause(UUID owner) {
        String cause = deadCause.get(owner);
        return cause == null ? "" : cause;
    }

    /** Complete the pending death only after the server confirms the player is actually dead. */
    public void resolveDead(UUID owner) {
        boolean changed = dead.remove(owner);
        changed |= deadKiller.remove(owner) != null;
        changed |= deadCause.remove(owner) != null;
        if (changed)
            setDirty();
    }

    /** Legacy destructive API retained for compatibility with older internal callers. */
    public boolean consumeDead(UUID owner) {
        boolean was = dead.remove(owner);
        if (was)
            setDirty();
        return was;
    }

    // ---- Downed player killed on logout
    // -----------------------------------------------------------------------------------

    public void markRespawnOnLogin(UUID owner) {
        if (respawnOnLogin.add(owner))
            setDirty();
    }

    public boolean needsRespawn(UUID owner) {
        return respawnOnLogin.contains(owner);
    }

    public void clearRespawn(UUID owner) {
        if (respawnOnLogin.remove(owner))
            setDirty();
    }

    // ---- Orphans
    // -----------------------------------------------------------------------------------

    public void markOrphan(UUID corpse) {
        orphans.add(corpse);
        setDirty();
    }

    /** True (once) if this corpse entity was already emptied from its snapshot and must vanish silently. */
    public boolean consumeOrphan(UUID corpse) {
        boolean was = orphans.remove(corpse);
        if (was)
            setDirty();
        return was;
    }
}
