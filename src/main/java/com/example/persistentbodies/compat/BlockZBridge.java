package com.example.persistentbodies.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Optional BlockZ bridge. BlockZ is never referenced at compile time: its classes are reached by reflection and its
 * corpse entity is created through the entity registry, so PPB loads and works the same without BlockZ.
 *
 * Every class/method name used here was read from the BlockZ 1.1.6-Beta sources:
 * <ul>
 * <li>{@code capability.PlayerBackpackProvider.PLAYER_BACKPACK} and {@code PlayerBackpack#getInventory()}
 * (4 slots: 0 backpack, 1 vest, 2 gloves, 3 mask)</li>
 * <li>{@code config.BlockZConfigs#getEnableCorpse()} / {@code #getCorpseDespawnTime()}</li>
 * <li>{@code network.NetworkHandler.CHANNEL} and {@code network.SyncBackpackS2C(int, ItemStack)}</li>
 * <li>the {@code blockz:corpse} entity and its saved-data format (OwnerUUID, OwnerName, DespawnTimer, Rotation,
 * Inventory[{Slot, ...item}])</li>
 * </ul>
 * If BlockZ changes any of them the affected feature degrades safely (the body falls back to dropping its items).
 */
public final class BlockZBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "blockz";
    public static final int CAP_SLOTS = 4;

    // Layout of BlockZ's corpse container (ModEvents#onLivingDeath, DayZInventoryMenu#layoutCorpseVicinitySlot).
    private static final int CORPSE_HOTBAR_START = 9;   // vanilla inventory index 0-35 -> +9 (hotbar, then pockets)
    private static final int CORPSE_SHOES = 5;
    private static final int CORPSE_PANTS = 3;
    private static final int CORPSE_SHIRT = 2;
    private static final int CORPSE_HEAD = 4;
    private static final int CORPSE_OFFHAND = 6;
    private static final int CORPSE_OVERFLOW_START = 45; // first slot after the 27 pocket slots
    private static final int CORPSE_SIZE = 256;
    // capability slot (backpack, vest, gloves, mask) -> corpse slot
    private static final int[] CAP_TO_CORPSE = { 0, 1, 8, 7 };
    // Curios slot BlockZ mirrors into each capability slot (see CuriosIntegration#importToCapability)
    private static final String[] MIRRORED_CURIO = { "back", "body", null, "head" };

    private static boolean resolved;
    private static boolean available;
    private static Field capabilityField;
    private static Method getBackpackInventory;
    private static Method getEnableCorpse;
    private static Method getDespawnTime;
    private static Field channelField;
    private static Constructor<?> syncPacketConstructor;

    private BlockZBridge() { }

    private static synchronized boolean resolve() {
        if (resolved) return available;
        resolved = true;
        try {
            if (!ModList.get().isLoaded(MOD_ID)) return false;
            Class<?> provider = Class.forName("com.yitianys.BlockZ.capability.PlayerBackpackProvider");
            capabilityField = provider.getField("PLAYER_BACKPACK");
            getBackpackInventory = Class.forName("com.yitianys.BlockZ.capability.PlayerBackpack").getMethod("getInventory");
            Class<?> configs = Class.forName("com.yitianys.BlockZ.config.BlockZConfigs");
            getEnableCorpse = configs.getMethod("getEnableCorpse");
            getDespawnTime = configs.getMethod("getCorpseDespawnTime");
            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("BlockZ is installed but its API could not be read; the BlockZ integration is disabled.", e);
            return false;
        }
        try {
            channelField = Class.forName("com.yitianys.BlockZ.network.NetworkHandler").getField("CHANNEL");
            syncPacketConstructor = Class.forName("com.yitianys.BlockZ.network.SyncBackpackS2C")
                    .getConstructor(int.class, ItemStack.class);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("BlockZ client sync packet not found; restored equipment will show up after the next relog.", e);
        }
        return available;
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /** BlockZ is present and every class PPB needs was found. */
    public static boolean isUsable() {
        return resolve();
    }

    /** True when BlockZ will create corpses for dead players (its own {@code enable_corpse} setting). */
    public static boolean isCorpseSystemEnabled() {
        if (!resolve()) return false;
        try {
            return Boolean.TRUE.equals(getEnableCorpse.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static int despawnSeconds() {
        try {
            if (getDespawnTime.invoke(null) instanceof Integer seconds && seconds > 0) return seconds;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // use the BlockZ default below
        }
        return 3600;
    }

    // ---- Capability slots (backpack, vest, gloves, mask)
    // -------------------------------------------------------------------------

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static IItemHandlerModifiable capInventory(LivingEntity entity) {
        if (!resolve()) return null;
        try {
            Capability capability = (Capability) capabilityField.get(null);
            if (capability == null) return null;
            LazyOptional lazy = entity.getCapability(capability);
            Object backpack = lazy.orElse(null);
            if (backpack == null) return null;
            return getBackpackInventory.invoke(backpack) instanceof IItemHandlerModifiable handler ? handler : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    public static ItemStack getSlot(LivingEntity entity, int slot) {
        IItemHandlerModifiable handler = capInventory(entity);
        if (handler == null || slot < 0 || slot >= CAP_SLOTS || slot >= handler.getSlots()) return ItemStack.EMPTY;
        return handler.getStackInSlot(slot);
    }

    public static void setSlot(LivingEntity entity, int slot, ItemStack stack) {
        IItemHandlerModifiable handler = capInventory(entity);
        if (handler != null && slot >= 0 && slot < CAP_SLOTS && slot < handler.getSlots()) {
            handler.setStackInSlot(slot, stack);
        }
    }

    /** @return what could not be placed (the whole stack when the slot is taken or BlockZ is unavailable) */
    public static ItemStack restoreSlot(LivingEntity entity, int slot, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        IItemHandlerModifiable handler = capInventory(entity);
        if (handler == null || slot < 0 || slot >= CAP_SLOTS || slot >= handler.getSlots()
                || !handler.getStackInSlot(slot).isEmpty()) {
            return stack;
        }
        handler.setStackInSlot(slot, stack.copy());
        return ItemStack.EMPTY;
    }

    /** Curios slot identifier that BlockZ mirrors into this capability slot, or null. */
    public static String mirroredCurioId(int capSlot) {
        return capSlot >= 0 && capSlot < MIRRORED_CURIO.length ? MIRRORED_CURIO[capSlot] : null;
    }

    /** Removes and returns everything left in the capability slots. */
    public static List<ItemStack> drainAll(LivingEntity entity) {
        List<ItemStack> drained = new ArrayList<>();
        for (int i = 0; i < CAP_SLOTS; i++) {
            ItemStack stack = getSlot(entity, i);
            if (stack.isEmpty()) continue;
            drained.add(stack.copy());
            setSlot(entity, i, ItemStack.EMPTY);
        }
        return drained;
    }

    /**
     * BlockZ sends the capability slots to the client on login. Anything restored later has to be sent again or
     * the client keeps showing empty slots until the next relog.
     */
    @SuppressWarnings("unchecked")
    public static void syncToClient(ServerPlayer player) {
        if (!resolve() || channelField == null || syncPacketConstructor == null) return;
        try {
            if (!(channelField.get(null) instanceof SimpleChannel channel)) return;
            for (int i = 0; i < CAP_SLOTS; i++) {
                Object packet = syncPacketConstructor.newInstance(i, getSlot(player, i).copy());
                channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Could not sync BlockZ equipment to {}", player.getName().getString(), e);
        }
    }

    // ---- Dead body -> BlockZ corpse
    // -------------------------------------------------------------------------------

    /**
     * Builds a BlockZ corpse for an offline player whose body was killed, with all of the body's items placed the same
     * way BlockZ places a dead player's items, and adds it to the world.
     *
     * Nothing is removed from {@code body}: the caller empties it only when this returns true, so a failure at any point
     * leaves the items where they were (and the caller falls back to dropping them).
     */
    public static boolean spawnDeathCorpse(ServerLevel level, UUID owner, String ownerName, double x, double y, double z,
            float yaw, Container body, List<String> extraKeys) {
        if (owner == null || !resolve()) return false;
        try {
            ResourceLocation id = new ResourceLocation(MOD_ID, "corpse");
            // getValue() returns a default entity type for unknown ids, so check the key first.
            if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) return false;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            Entity corpse = type == null ? null : type.create(level);
            if (corpse == null) return false;

            Map<Integer, ItemStack> layout = layoutForCorpse(body, extraKeys);
            if (layout == null) return false;
            ListTag inventory = new ListTag();
            for (Map.Entry<Integer, ItemStack> entry : layout.entrySet()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) (int) entry.getKey());
                entry.getValue().save(itemTag);
                inventory.add(itemTag);
            }

            corpse.moveTo(x, y, z, yaw, 0.0F);
            // BlockZ stores "Rotation" as a float in its own data (overriding the vanilla list), so start from a
            // complete save of the new entity and overwrite only BlockZ's fields.
            CompoundTag tag = corpse.saveWithoutId(new CompoundTag());
            tag.putUUID("OwnerUUID", owner);
            tag.putString("OwnerName", ownerName == null ? "" : ownerName);
            tag.putInt("DespawnTimer", despawnSeconds() * 20);
            tag.putFloat("Rotation", yaw);
            tag.put("Inventory", inventory);
            corpse.load(tag);
            return level.addFreshEntity(corpse);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Could not create the BlockZ corpse for {}; falling back to dropping the items.", ownerName, e);
            return false;
        }
    }

    /**
     * PPB body slot -> BlockZ corpse slot.
     * 0-35 (hotbar + inventory) -> +9; 36 boots -> 5; 37 leggings -> 3; 38 chest -> 2; 39 head -> 4; 40 offhand -> 6;
     * backpack/vest/gloves/mask -> 0/1/8/7. Curios accessories have no counterpart in BlockZ's corpse, so they go
     * after the pocket slots (they show up as ordinary loot).
     *
     * @return null if the items do not fit into BlockZ's 256-slot container (nothing is converted then)
     */
    private static Map<Integer, ItemStack> layoutForCorpse(Container body, List<String> extraKeys) {
        Map<Integer, ItemStack> layout = new TreeMap<>();
        for (int i = 0; i < 36; i++) put(layout, CORPSE_HOTBAR_START + i, body.getItem(i));
        put(layout, CORPSE_SHOES, body.getItem(36));
        put(layout, CORPSE_PANTS, body.getItem(37));
        put(layout, CORPSE_SHIRT, body.getItem(38));
        put(layout, CORPSE_HEAD, body.getItem(39));
        put(layout, CORPSE_OFFHAND, body.getItem(40));

        int overflow = CORPSE_OVERFLOW_START;
        for (int k = 0; k < extraKeys.size(); k++) {
            ItemStack stack = body.getItem(ExtraSlots.FIRST_EXTRA_SLOT + k);
            if (stack.isEmpty()) continue;
            int target = -1;
            String key = extraKeys.get(k);
            if (key.startsWith(ExtraSlots.BLOCKZ)) {
                try {
                    int capSlot = Integer.parseInt(key.substring(ExtraSlots.BLOCKZ.length()));
                    if (capSlot >= 0 && capSlot < CAP_TO_CORPSE.length) target = CAP_TO_CORPSE[capSlot];
                } catch (NumberFormatException ignored) {
                    // handled below as an overflow item
                }
            }
            if (target < 0 || layout.containsKey(target)) target = overflow++;
            if (target >= CORPSE_SIZE) return null;
            put(layout, target, stack);
        }
        return layout;
    }

    private static void put(Map<Integer, ItemStack> layout, int slot, ItemStack stack) {
        if (!stack.isEmpty()) layout.put(slot, stack.copy());
    }
}
