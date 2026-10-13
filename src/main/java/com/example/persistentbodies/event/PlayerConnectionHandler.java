package com.example.persistentbodies.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import com.example.persistentbodies.logic.DeathRetryPolicy;
import org.slf4j.Logger;
import com.example.persistentbodies.compat.BlockZBridge;
import com.example.persistentbodies.compat.ExtraSlots;
import com.example.persistentbodies.config.CorpseConfig;
import com.example.persistentbodies.curios.CuriosBridge;
import com.example.persistentbodies.downed.DownedManager;
import com.example.persistentbodies.entity.PlayerCorpseEntity;
import com.example.persistentbodies.registry.ModEntities;
import com.example.persistentbodies.storage.CorpseSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Handles logout transfer and login recovery on the logical server thread. */
public class PlayerConnectionHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Keeps the body's chunk loaded (entity-ticking) while the owner is being reunited with it. */
    private static final TicketType<UUID> RECOVERY_TICKET = TicketType.create("persistentbodies_recovery",
            UUID::compareTo, 600);
    /** Ticks to wait for the body entity to show up before looking at the backup snapshot. */
    private static final int WAIT_BEFORE_SNAPSHOT = 100;
    /** Ticks after which we stop trying (the record is kept, so the next login retries). */
    private static final int GIVE_UP = 1200;

    /** A player that was brought to their body and is waiting for the body entity to finish loading. */
    private static final class PendingRecovery {
        final MinecraftServer server;
        final UUID player;
        final UUID corpse;
        final ResourceKey<Level> dimension;
        final ChunkPos chunk;
        int age;

        PendingRecovery(MinecraftServer server, UUID player, UUID corpse, ResourceKey<Level> dimension,
                ChunkPos chunk) {
            this.server = server;
            this.player = player;
            this.corpse = corpse;
            this.dimension = dimension;
            this.chunk = chunk;
        }
    }

    private final List<PendingRecovery> pending = new ArrayList<>();
    /**
     * Retry schedule per owner. The markers themselves are durable and live in SavedData until the actual death is
     * confirmed; this map only holds the in-memory backoff and is rebuilt on demand (restart, relog).
     */
    private final java.util.Map<UUID, DeathRetryPolicy> deathRetries = new java.util.HashMap<>();
    /**
     * The ServerPlayer instance created by each player's latest login. A pending offline death may only ever be applied
     * to THIS instance: a respawn (or any other death) creates a new instance, and that one must never be killed by a
     * marker that belongs to the previous life.
     */
    private final java.util.Map<UUID, ServerPlayer> loginInstance = new java.util.HashMap<>();

    // ---- Logout
    // ----------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        deathWatch.remove(player.getUUID());
        loginInstance.remove(player.getUUID(), player);
        // Case B: a downed player who disconnects dies for real (vanilla death rules) and gets NO body. The call is
        // idempotent and also cancels any revive in which this player took part (as reviver or as target).
        if (DownedManager.handleLogout(player)) return;
        if (!CorpseConfig.ENABLED.get() || player.isDeadOrDying()) return;
        CorpseSavedData data = CorpseSavedData.get(player.serverLevel());
        if (data.get(player.getUUID()) != null) return;
        if (data.isDeadPending(player.getUUID())) {
            // The player disconnected again before their death could be completed.
            // A new body would be a second, unrelated stand-in for someone who is supposed to be dead.
            LOGGER.warn("{} left while a death is still pending; no body is created and the marker is kept.",
                    player.getGameProfile().getName());
            return;
        }

        PlayerCorpseEntity corpse = ModEntities.PLAYER_CORPSE.get().create(player.serverLevel());
        if (corpse == null) return;
        corpse.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        String textureValue = "";
        String textureSignature = "";
        // Dedicated servers do not always have the texture property attached to the
        // live GameProfile. Ask the authenticated session service for it before saving.
        com.mojang.authlib.GameProfile skinProfile = player.getGameProfile();
        try {
            skinProfile = player.getServer().getSessionService().fillProfileProperties(skinProfile, true);
        } catch (RuntimeException ignored) {
            // Offline-mode servers may not have a session service result; use the profile as-is.
        }
        var textureProperties = skinProfile.getProperties().get("textures");
        if (textureProperties != null && !textureProperties.isEmpty()) {
            Property texture = textureProperties.iterator().next();
            textureValue = texture.getValue();
            if (texture.hasSignature()) textureSignature = texture.getSignature();
        }
        corpse.initialize(player.getUUID(), player.getGameProfile().getName(), textureValue, textureSignature, player.serverLevel().getGameTime());
        // The body inherits the player's current health; hits on the body are hits on the player.
        corpse.setHealthState(player.getHealth(), player.getMaxHealth());
        // The item in the selected hotbar slot is the body's "main hand" (matters for totems).
        corpse.setSelectedSlot(player.getInventory().selected);
        for (int i = 0; i < 41; i++) corpse.setItem(i, player.getInventory().getItem(i).copy());
        // Extras: every Curios slot plus BlockZ's backpack/vest/gloves/mask. They are only COPIED into the body here;
        // the removals run after the body exists, so a failure can never lose (or duplicate) items.
        List<Runnable> extraRemovals = ExtraSlots.collect(player, corpse::addExtra);
        if (!player.serverLevel().addFreshEntity(corpse)) return;

        // Remember where the body is (dimension + coordinates) and keep a backup snapshot of it.
        data.register(player.getUUID(), corpse.getUUID(), player.serverLevel().dimension(),
                corpse.getX(), corpse.getY(), corpse.getZ(), corpse.getYRot());
        corpse.activateSnapshot();
        // Only now take the items away from the player.
        ExtraSlots.runAll(extraRemovals);
        for (int i = 0; i < player.getInventory().getContainerSize() && i < 41; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        player.getInventory().setChanged();
    }

    // ---- Login
    // -----------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        loginInstance.put(player.getUUID(), player);
        // Sends the downed list to the joiner and schedules the respawn of a player killed while downed on logout.
        DownedManager.onLogin(player);
        CorpseSavedData data = CorpseSavedData.get(player.serverLevel());
        // The body was killed while the player was away: its items already went to a BlockZ corpse (or were
        // dropped), now the player dies too.
        if (data.isDeadPending(player.getUUID())) {
            LOGGER.info("{} logged in with a pending death marker; finishing it before any body recovery.",
                    player.getGameProfile().getName());
            // Never run ordinary body recovery while a death is pending: the old snapshot must not be restored over a
            // player whose body was already killed. If this attempt fails the marker stays and the server tick retries.
            attemptPendingDeath(player, data);
            return;
        }
        if (!CorpseConfig.RECOVER_ON_JOIN.get()) return;
        CorpseSavedData.Record record = data.getRecord(player.getUUID());
        if (record == null) return;

        MinecraftServer server = player.getServer();
        ServerLevel bodyLevel = null;
        if (record.hasLocation) {
            bodyLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(record.dimension)));
        }
        ChunkPos chunk;
        if (bodyLevel != null) {
            // Wherever the player logged back in, they continue where their body is.
            if (bodyLevel != player.serverLevel() || player.distanceToSqr(record.x, record.y, record.z) > 4.0D) {
                player.teleportTo(bodyLevel, record.x, record.y, record.z, record.yaw, player.getXRot());
            }
            chunk = new ChunkPos(BlockPos.containing(record.x, record.y, record.z));
        } else {
            // Old record without coordinates (or the dimension no longer exists): look around the player.
            bodyLevel = player.serverLevel();
            chunk = player.chunkPosition();
        }
        // The body's chunk may be unloaded and its entities are loaded asynchronously, so force-load it with a
        // temporary ticket (it expires by itself, nothing is persisted) and finish the recovery in a few ticks.
        bodyLevel.getChunkSource().addRegionTicket(RECOVERY_TICKET, chunk, 2, player.getUUID());
        pending.add(new PendingRecovery(server, player.getUUID(), record.corpse, bodyLevel.dimension(), chunk));
    }

    /** True when the player carries nothing at all: vanilla slots, BlockZ equipment and every Curios slot. */
    private static boolean carriesNothing(ServerPlayer player) {
        if (!player.getInventory().isEmpty()) return false;
        for (int i = 0; i < BlockZBridge.CAP_SLOTS; i++) {
            if (!BlockZBridge.getSlot(player, i).isEmpty()) return false;
        }
        for (CuriosBridge.SlotRef ref : CuriosBridge.listSlots(player)) {
            if (!ref.stack().isEmpty()) return false;
        }
        return true;
    }

    /**
     * Kills a player whose body died while they were offline, without creating anything a second time.
     *
     * The body's items were already handed to a BlockZ corpse (or dropped), so the player normally carries nothing.
     * BlockZ would still create an empty corpse for any death, so in that case keepInventory is switched on for the
     * duration of the kill: BlockZ skips its corpse and vanilla drops nothing (there is nothing to lose). If the
     * player does carry something, the death is left completely normal, so those items end up in a regular corpse
     * or drop instead of being lost. The previous rule values are always restored.
     */
    private static boolean dieAfterOfflineDeath(ServerPlayer player, CorpseSavedData data) {
        GameRules rules = player.serverLevel().getGameRules();
        boolean worldKeepsInventory = rules.getBoolean(GameRules.RULE_KEEPINVENTORY);
        CorpseSavedData.Record record = data.getRecord(player.getUUID());
        if (worldKeepsInventory) {
            // The body died under keepInventory: it kept its record. The owner gets everything back, then dies
            // keeping it (vanilla copies the inventory on respawn).
            if (record != null) recoverFromSnapshot(player, data, record, true);
        }
        boolean forceKeepInventory = !worldKeepsInventory && carriesNothing(player);
        GameRules.BooleanValue keepRule = rules.getRule(GameRules.RULE_KEEPINVENTORY);
        GameRules.BooleanValue messageRule = rules.getRule(GameRules.RULE_SHOWDEATHMESSAGES);
        boolean previousMessages = messageRule.get();
        MinecraftServer server = player.getServer();
        boolean deathCompleted;
        try {
            if (forceKeepInventory) keepRule.set(true, server);
            // The body's death was already announced (when messages are on); do not announce it twice.
            if (CorpseConfig.MESSAGES.get() && CorpseConfig.SUPPRESS_LOGIN_DEATH_MESSAGE.get()) {
                messageRule.set(false, server);
            }
            // Explicitly bypass this mod's downed interception too. Damage-type tags alone are not a reliable guard
            // for a required offline death.
            deathCompleted = DownedManager.killForPendingOfflineDeath(player);
        } finally {
            keepRule.set(worldKeepsInventory, server);
            messageRule.set(previousMessages, server);
        }
        return deathCompleted;
    }

    /**
     * One guarded attempt to finish a pending death. Never throws: any failure is logged with its stack trace, the
     * durable marker is kept and the next attempt is scheduled with backoff (no retry limit, the marker is never dropped).
     *
     * @return true when the death is confirmed and the marker was resolved.
     */
    private boolean attemptPendingDeath(ServerPlayer player, CorpseSavedData data) {
        UUID id = player.getUUID();
        long now = player.getServer().getTickCount();
        if (loginInstance.get(id) != player) {
            // A different ServerPlayer instance can mean another mod respawned the player after PlayerRevive
            // intercepted the first attempt. The durable marker is only cleared after a confirmed death, so transfer
            // the pending death to the current instance instead of silently allowing the owner to live.
            LOGGER.warn("{} has a new player instance while an offline death is pending; retrying on the current instance.",
                    player.getGameProfile().getName());
            loginInstance.put(id, player);
        }
        DeathRetryPolicy retry = deathRetries.computeIfAbsent(id, k -> new DeathRetryPolicy());
        boolean done = false;
        try {
            done = tryFinishOfflineDeath(player, data, data.getDeadKiller(id), data.getDeadCause(id));
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Unexpected error while finishing the pending death of {}", player.getGameProfile().getName(), e);
        }
        if (done) {
            deathRetries.remove(id);
        } else {
            int delay = retry.failed(now);
            LOGGER.warn("Pending death of {} not completed (attempt {}); marker kept, next attempt in {} ticks.",
                    player.getGameProfile().getName(), retry.failures(), delay);
        }
        return done;
    }

    /** Ticks during which a player that was just killed at login is watched for a "resurrection". */
    private static final int DEATH_WATCH_TICKS = 200;
    /** How many times a resurrected player is killed again before giving up (and logging it). */
    private static final int DEATH_WATCH_MAX_RETRIES = 3;

    /** The instance that was killed. A respawn creates a NEW ServerPlayer, so a different instance means "respawned". */
    private static final class DeathWatch {
        final ServerPlayer killed;
        final String killer;
        final String cause;
        int ticksLeft = DEATH_WATCH_TICKS;
        int retries;
        int screenResends = 3;

        DeathWatch(ServerPlayer killed, String killer, String cause) {
            this.killed = killed;
            this.killer = killer;
            this.cause = cause;
        }
    }

    private final java.util.Map<UUID, DeathWatch> deathWatch = new java.util.HashMap<>();

    private boolean tryFinishOfflineDeath(ServerPlayer player, CorpseSavedData data, String killer, String cause) {
        boolean alreadyDead = player.isDeadOrDying();
        if (!alreadyDead && !dieAfterOfflineDeath(player, data)) return false;
        // Persist the dead player BEFORE clearing the marker. If saving fails, keep the marker and retry; a respawned
        // instance will also receive the still-pending death instead of silently becoming alive.
        try {
            player.getServer().getPlayerList().saveAll();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Could not save the player data after the offline death of {} (pending marker retained)",
                    player.getGameProfile().getName(), e);
            return false;
        }
        data.resolveDead(player.getUUID());
        CorpseSavedData.flush(player.serverLevel());
        LOGGER.info("Pending death of {} resolved.", player.getGameProfile().getName());
        try {
            showSleepingDeathScreen(player, killer, cause);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Could not send the sleeping-death screen to {}", player.getGameProfile().getName(), e);
        }
        deathWatch.put(player.getUUID(), new DeathWatch(player, killer, cause));
        return true;
    }

    /**
     * Watches players killed at login. A different ServerPlayer instance means the player clicked "Respawn" (that is a
     * normal, finished death: never kill it again). The SAME instance with health above 0 means something resurrected it
     * after the kill: it is killed again (limited retries) and the diagnostics are logged.
     */
    private void processDeathWatch(MinecraftServer server) {
        if (deathWatch.isEmpty()) return;
        Iterator<java.util.Map.Entry<UUID, DeathWatch>> it = deathWatch.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<UUID, DeathWatch> entry = it.next();
            DeathWatch watch = entry.getValue();
            ServerPlayer current = server.getPlayerList().getPlayer(entry.getKey());
            if (current == null) { // went offline
                it.remove();
                continue;
            }
            if (current != watch.killed) { // respawned: the death is complete
                it.remove();
                continue;
            }
            if (current.getHealth() <= 0.0F) { // still dead, waiting for the respawn button
                if (watch.screenResends > 0) {
                    showSleepingDeathScreen(current, watch.killer, watch.cause);
                    watch.screenResends--;
                }
                if (--watch.ticksLeft <= 0) it.remove();
                continue;
            }
            String name = current.getGameProfile().getName();
            if (watch.retries >= DEATH_WATCH_MAX_RETRIES) {
                LOGGER.error("{} keeps coming back to life after the offline death (health={}); giving up.",
                        name, current.getHealth());
                it.remove();
                continue;
            }
            watch.retries++;
            LOGGER.error("{} was brought back to life after the offline death (same player instance, health={}); killing again ({}/{}).",
                    name, current.getHealth(), watch.retries, DEATH_WATCH_MAX_RETRIES);
            try {
                CorpseSavedData data = CorpseSavedData.get(current.serverLevel());
                if (dieAfterOfflineDeath(current, data)) {
                    server.getPlayerList().saveAll();
                    showSleepingDeathScreen(current, watch.killer, watch.cause);
                    watch.ticksLeft = DEATH_WATCH_TICKS;
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("Could not kill {} again", name, e);
            }
        }
    }

    /**
     * The death screen of a player whose body was killed while they slept: "Te mataron durmiendo: X". A second
     * combat-kill packet makes the client replace the vanilla death screen (which shows the generic death message).
     */
    private static void showSleepingDeathScreen(ServerPlayer player, String killer, String cause) {
        if (player.isAlive() || player.connection == null) return;
        Component message;
        if (!killer.isEmpty()) {
            message = Component.translatable("message.persistentbodies.killed_sleeping", killer);
        } else if (!cause.isEmpty()) {
            Component vanillaCause = Component.translatable("death.attack." + cause, player.getDisplayName());
            message = Component.translatable("message.persistentbodies.killed_sleeping_cause", vanillaCause);
        } else {
            message = Component.translatable("message.persistentbodies.killed_sleeping_unknown");
        }
        player.connection.send(new ClientboundPlayerCombatKillPacket(player.getId(), message));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        processDeathWatch(event.getServer());
        processPendingDeaths(event.getServer());
        if (pending.isEmpty()) return;
        Iterator<PendingRecovery> it = pending.iterator();
        while (it.hasNext()) {
            if (process(it.next())) it.remove();
        }
    }

    /**
     * Once per second: every owner with a durable pending-death marker who is online and whose retry is due gets another
     * attempt. The marker set in SavedData is the single source of truth, so this also covers owners who were marked
     * while online and survives restarts without any in-memory list.
     */
    private void processPendingDeaths(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        CorpseSavedData data = CorpseSavedData.get(server.overworld());
        java.util.Set<UUID> owners = data.pendingDeadOwners();
        if (owners.isEmpty()) {
            deathRetries.clear();
            return;
        }
        deathRetries.keySet().removeIf(id -> !owners.contains(id));
        for (UUID owner : owners) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) {
                deathRetries.remove(owner); // offline: the marker stays for their next login
                continue;
            }
            DeathRetryPolicy retry = deathRetries.get(owner);
            if (retry != null && !retry.isDue(server.getTickCount())) continue;
            attemptPendingDeath(player, data);
        }
    }

    /** @return true when this recovery is finished (successfully or not) and can be dropped. */
    private boolean process(PendingRecovery p) {
        p.age++;
        ServerPlayer player = p.server.getPlayerList().getPlayer(p.player);
        if (player == null) return true; // went offline again: the record stays for the next login
        CorpseSavedData data = CorpseSavedData.get(player.serverLevel());
        CorpseSavedData.Record record = data.getRecord(p.player);
        if (record == null || !record.corpse.equals(p.corpse)) return true; // already resolved elsewhere

        ServerLevel level = p.server.getLevel(p.dimension);
        if (level != null) {
            Entity found = level.getEntity(p.corpse);
            if (found instanceof PlayerCorpseEntity corpse && !corpse.isRemoved()) {
                recoverFromBody(player, data, corpse);
                return true;
            }
        }
        if (p.age < WAIT_BEFORE_SNAPSHOT) return false;
        boolean chunkLoaded = level != null && level.hasChunk(p.chunk.x, p.chunk.z);
        if (level == null || chunkLoaded) {
            // The chunk is loaded and the body still is not there: it is really gone. Use the backup.
            recoverFromSnapshot(player, data, record);
            return true;
        }
        if (p.age >= GIVE_UP) {
            player.sendSystemMessage(Component.translatable("message.persistentbodies.body_unreachable"));
            return true;
        }
        return false;
    }

    private static void recoverFromBody(ServerPlayer player, CorpseSavedData data, PlayerCorpseEntity corpse) {
        if (!player.getUUID().equals(corpse.getOwnerId()) || corpse.isRecovering()) return;
        corpse.setRecovering(true);
        // Hand the body's remaining health (and totem effects) back to the player.
        player.setHealth(Math.min(corpse.getHealth(), player.getMaxHealth()));
        applyEffects(player, corpse.getRegenTicks(), corpse.getFireResistTicks(), corpse.getAbsorptionTicks(),
                corpse.getAbsorption());
        boolean remaining = transfer(player, corpse, corpse.getExtraKeys());
        player.getInventory().setChanged();
        BlockZBridge.syncToClient(player);
        corpse.setRecovering(false);
        if (!remaining && corpse.isEmpty()) {
            data.remove(player.getUUID());
            corpse.discard();
        }
    }

    /** The body entity cannot be found: rebuild everything from the backup kept in the world data. */
    private static void recoverFromSnapshot(ServerPlayer player, CorpseSavedData data, CorpseSavedData.Record record) {
        recoverFromSnapshot(player, data, record, false);
    }

    /** @param entityIsGone true when the body entity is known to be gone (died under keepInventory): no orphan, no message */
    private static void recoverFromSnapshot(ServerPlayer player, CorpseSavedData data, CorpseSavedData.Record record,
            boolean entityIsGone) {
        CompoundTag snapshot = record.snapshot;
        UUID corpseId = record.corpse;
        data.remove(player.getUUID());
        if (snapshot == null) {
            if (!entityIsGone) player.sendSystemMessage(Component.translatable("message.persistentbodies.body_lost"));
            return;
        }
        // If the real entity ever loads later it must vanish without dropping anything.
        if (!entityIsGone) data.markOrphan(corpseId);
        SimpleContainer source = new SimpleContainer(PlayerCorpseEntity.TOTAL_SLOTS);
        PlayerCorpseEntity.readItems(snapshot.getList("Items", Tag.TAG_COMPOUND), source);
        float maxHealth = snapshot.contains("MaxHealth") ? snapshot.getFloat("MaxHealth") : 20.0F;
        float health = snapshot.contains("Health") ? snapshot.getFloat("Health") : maxHealth;
        player.setHealth(Math.min(Math.max(health, 0.5F), player.getMaxHealth()));
        applyEffects(player, snapshot.getInt("Regen"), snapshot.getInt("Fire"), snapshot.getInt("AbsTicks"),
                snapshot.getFloat("Abs"));
        transfer(player, source, PlayerCorpseEntity.readKeys(snapshot));
        // Whatever did not fit has no body to stay on: drop it at the player's feet.
        for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack left = source.getItem(i);
            if (!left.isEmpty()) player.drop(left.copy(), false);
        }
        player.getInventory().setChanged();
        BlockZBridge.syncToClient(player);
        if (!entityIsGone) player.sendSystemMessage(Component.translatable("message.persistentbodies.restored_from_backup"));
    }

    /**
     * Moves the items of a body (or snapshot) to the player, each one to its original slot when free.
     * Anything that does not fit stays in the source.
     *
     * @return true if something remained in the source
     */
    private static boolean transfer(ServerPlayer player, Container source, List<String> extraKeys) {
        boolean remaining = false;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 41; i++) {
            ItemStack stored = source.getItem(i);
            if (stored.isEmpty()) continue;
            ItemStack moving = stored.copy();
            if (inventory.getItem(i).isEmpty()) {
                // Main inventory, hotbar, armor and offhand all use the same indices as the body.
                inventory.setItem(i, moving);
                moving = ItemStack.EMPTY;
            } else {
                inventory.add(moving);
            }
            if (moving.isEmpty()) source.setItem(i, ItemStack.EMPTY);
            else { source.setItem(i, moving); remaining = true; }
        }
        // Extras (Curios slots, BlockZ backpack/vest/gloves/mask) go back to the slot their key names; whatever
        // cannot be placed goes to the inventory, and stays on the body if even that is full.
        for (int k = 0; k < extraKeys.size(); k++) {
            int index = ExtraSlots.FIRST_EXTRA_SLOT + k;
            ItemStack stored = source.getItem(index);
            if (stored.isEmpty()) continue;
            ItemStack leftover = ExtraSlots.restore(player, extraKeys.get(k), stored.copy());
            if (!leftover.isEmpty()) inventory.add(leftover);
            if (leftover.isEmpty()) source.setItem(index, ItemStack.EMPTY);
            else { source.setItem(index, leftover); remaining = true; }
        }
        return remaining;
    }

    private static void applyEffects(ServerPlayer player, int regenTicks, int fireTicks, int absorptionTicks,
            float absorption) {
        if (regenTicks > 0) player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, regenTicks, 1));
        if (fireTicks > 0) player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, fireTicks, 0));
        if (absorptionTicks > 0 && absorption > 0.0F) {
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, absorptionTicks, 1));
            player.setAbsorptionAmount(absorption);
        }
    }
}
