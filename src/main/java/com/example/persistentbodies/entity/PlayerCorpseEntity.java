package com.example.persistentbodies.entity;

import java.util.UUID;
import javax.annotation.Nullable;
import com.example.persistentbodies.compat.BlockZBridge;
import com.example.persistentbodies.compat.ExtraSlots;
import com.example.persistentbodies.downed.DownedManager;
import com.example.persistentbodies.config.CorpseConfig;
import com.example.persistentbodies.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.GameRules;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import com.example.persistentbodies.storage.CorpseSavedData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Persistent corpse container. A prone player renderer is not yet implemented
 * in this MVP.
 */
public class PlayerCorpseEntity extends Entity implements MenuProvider, Container {
    private static final org.slf4j.Logger PB_LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final EntityDataAccessor<String> OWNER_NAME = SynchedEntityData.defineId(PlayerCorpseEntity.class,
            EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> OWNER_UUID = SynchedEntityData.defineId(PlayerCorpseEntity.class,
            EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_VALUE = SynchedEntityData.defineId(PlayerCorpseEntity.class,
            EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN_SIGNATURE = SynchedEntityData
            .defineId(PlayerCorpseEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> CURIOS_SUPPORT = SynchedEntityData
            .defineId(PlayerCorpseEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> STORED_INVISIBLE = SynchedEntityData
            .defineId(PlayerCorpseEntity.class, EntityDataSerializers.BOOLEAN);
    // Worn armor is mirrored into synched data so every client can render it in
    // real time.
    // The item container itself only exists on the server (clients only fill it
    // while a menu is open).
    private static final EntityDataAccessor<ItemStack> HEAD_STACK = SynchedEntityData.defineId(PlayerCorpseEntity.class,
            EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> CHEST_STACK = SynchedEntityData
            .defineId(PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> LEGS_STACK = SynchedEntityData.defineId(PlayerCorpseEntity.class,
            EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<ItemStack> FEET_STACK = SynchedEntityData.defineId(PlayerCorpseEntity.class,
            EntityDataSerializers.ITEM_STACK);
    /** 36 inventory + 4 armor + offhand. Extra slots (Curios, BlockZ equipment) follow. */
    public static final int VANILLA_SLOTS = ExtraSlots.FIRST_EXTRA_SLOT;
    /** Hard upper bound for extra slots; the configurable limit can only be lower. */
    public static final int MAX_EXTRAS = 64;
    public static final int TOTAL_SLOTS = VANILLA_SLOTS + MAX_EXTRAS;
    private final SimpleContainer items = new SimpleContainer(TOTAL_SLOTS);
    /** Key of the k-th extra slot (see {@link ExtraSlots}); its stack is at container index VANILLA_SLOTS + k. */
    private final List<String> extraKeys = new ArrayList<>();
    private boolean curiosSupport;
    private UUID ownerId;

    private long createdAt;
    private boolean recovering;
    // Health of the player at the moment of logout. The body takes damage in their
    // place and the
    // remaining health is handed back to the player on login (see
    // PlayerConnectionHandler).
    private float health = 20.0F;
    private float maxHealth = 20.0F;
    private final List<MobEffectInstance> storedEffects = new ArrayList<>();
    private boolean dying;
    private int hurtFlash; // client-side: ticks left of the red "hit" tint
    // Hotbar slot the player had selected at logout: the item in it is the "main hand" (totems!).
    private int selectedSlot;
    // Totem of Undying aftermath, simulated on the body (Regeneration II 45s, Absorption II 5s, Fire Resistance 40s).
    private int regenTicks;
    private int fireResistTicks;
    private int absorptionTicks;
    private float absorption;
    // True once the SavedData record belongs to this entity and snapshots may be pushed.
    private boolean snapshotActive;
    // Vanilla-like invulnerability frames: without them a cactus/lava/fire hurt the body every single tick.
    private int hurtCooldown;
    private float lastHurt;

    public PlayerCorpseEntity(EntityType<? extends PlayerCorpseEntity> type, Level level) {
        super(type, level);
        // Any change made through slots, hoppers, commands, etc. refreshes the visible
        // armor.
        items.addListener(container -> {
            syncEquipment();
            pushSnapshot();
        });
    }

    /**
     * Armor currently worn by the body, as known on this side (server: real data,
     * client: synched copy).
     */
    public ItemStack getArmorStack(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> entityData.get(HEAD_STACK);
            case CHEST -> entityData.get(CHEST_STACK);
            case LEGS -> entityData.get(LEGS_STACK);
            case FEET -> entityData.get(FEET_STACK);
            default -> ItemStack.EMPTY;
        };
    }

    private void syncEquipment() {
        if (level().isClientSide)
            return;
        syncSlot(HEAD_STACK, 39);
        syncSlot(CHEST_STACK, 38);
        syncSlot(LEGS_STACK, 37);
        syncSlot(FEET_STACK, 36);
    }

    private void syncSlot(EntityDataAccessor<ItemStack> accessor, int containerIndex) {
        ItemStack current = items.getItem(containerIndex);
        // ItemStack has no equals(): compare by content or the data would be resent on
        // every call.
        if (!ItemStack.matches(current, entityData.get(accessor)))
            entityData.set(accessor, current.copy());
    }

    public void initialize(UUID owner, String name, String textureValue, String textureSignature, long created) {
        ownerId = owner;
        entityData.set(OWNER_UUID, owner == null ? "" : owner.toString());
        entityData.set(OWNER_NAME, name == null ? "Unknown" : name);
        entityData.set(SKIN_VALUE, textureValue == null ? "" : textureValue);
        entityData.set(SKIN_SIGNATURE, textureSignature == null ? "" : textureSignature);
        createdAt = created;
        setCustomName(Component.literal(getOwnerName()));
        // The name tag is rendered by the client renderer only within two blocks.
        setCustomNameVisible(false);
    }

    public void initialize(UUID owner, String name, String skin, long created) {
        initialize(owner, name, "", "", created);
    }

    @Nullable
    public UUID getOwnerId() {
        String value = entityData.get(OWNER_UUID);
        if (value == null || value.isEmpty())
            return ownerId;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return ownerId;
        }
    }

    public String getOwnerName() {
        return entityData.get(OWNER_NAME);
    }

    public String getSkinName() {
        return getOwnerName();
    }

    public String getSkinTextureValue() {
        return entityData.get(SKIN_VALUE);
    }

    public String getSkinTextureSignature() {
        return entityData.get(SKIN_SIGNATURE);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isRecovering() {
        return recovering;
    }

    public boolean hasCuriosSupport() {
        return entityData.get(CURIOS_SUPPORT);
    }

    public void setCuriosSupport(boolean value) {
        curiosSupport = value;
        entityData.set(CURIOS_SUPPORT, value);
    }

    public boolean hasCuriosSlots() {
        for (int i = VANILLA_SLOTS; i < VANILLA_SLOTS + extraKeys.size(); i++)
            if (!items.getItem(i).isEmpty())
                return true;
        return false;
    }

    // ---- Extra slots (Curios + BlockZ equipment)
    // -------------------------------------------------------------------------

    /** Keys of the extra slots this body holds, in container order. Server side only (the client gets them in the menu). */
    public List<String> getExtraKeys() {
        return new ArrayList<>(extraKeys);
    }

    /** Adds an extra stack under {@code key}. @return false when the body is full (the stack was not stored) */
    public boolean addExtra(String key, ItemStack stack) {
        if (extraKeys.size() >= MAX_EXTRAS)
            return false;
        if (!stack.isEmpty() && countStoredExtraItems() >= CorpseConfig.MAX_EXTRA_SLOTS.get())
            return false;
        extraKeys.add(key);
        items.setItem(VANILLA_SLOTS + extraKeys.size() - 1, stack);
        setCuriosSupport(true);
        return true;
    }

    private int countStoredExtraItems() {
        int count = 0;
        for (int i = 0; i < extraKeys.size(); i++)
            if (!items.getItem(VANILLA_SLOTS + i).isEmpty())
                count++;
        return count;
    }

    public static ListTag writeKeys(List<String> keys) {
        ListTag list = new ListTag();
        for (String key : keys)
            list.add(StringTag.valueOf(key));
        return list;
    }

    /**
     * Reads the extra slot keys of an entity or snapshot tag. Data written before keys existed has Curios stacks at
     * container indices 41+ without any key: those get "legacy:n" keys so they can still be returned.
     */
    public static List<String> readKeys(CompoundTag tag) {
        List<String> keys = new ArrayList<>();
        if (tag.contains("ExtraKeys", Tag.TAG_LIST)) {
            ListTag list = tag.getList("ExtraKeys", Tag.TAG_STRING);
            for (int i = 0; i < list.size() && keys.size() < MAX_EXTRAS; i++)
                keys.add(list.getString(i));
            return keys;
        }
        int highest = -1;
        ListTag stored = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < stored.size(); i++) {
            CompoundTag entry = stored.getCompound(i);
            if (entry.contains("Slot", Tag.TAG_ANY_NUMERIC))
                highest = Math.max(highest, entry.getByte("Slot") & 255);
        }
        for (int slot = VANILLA_SLOTS; slot <= highest && keys.size() < MAX_EXTRAS; slot++)
            keys.add(ExtraSlots.LEGACY + (slot - VANILLA_SLOTS));
        return keys;
    }

    public void setRecovering(boolean value) {
        recovering = value;
        // Item changes are not pushed while recovering (one push at the end is enough).
        if (!value)
            pushSnapshot();
    }

    public int getSelectedSlot() {
        return selectedSlot;
    }

    public void setSelectedSlot(int slot) {
        selectedSlot = Mth.clamp(slot, 0, 8);
    }

    public int getRegenTicks() {
        return regenTicks;
    }

    public int getFireResistTicks() {
        return fireResistTicks;
    }

    public int getAbsorptionTicks() {
        return absorptionTicks;
    }

    public float getAbsorption() {
        return absorption;
    }

    // ---- Backup snapshot in the world's SavedData
    // -------------------------------------------------------------
    /**
     * Vanilla SimpleContainer#createTag/#fromTag do NOT store slot numbers: reading re-packs the stacks into the
     * first free slots. For the body that moved worn armor (36-39) and the offhand into the main inventory every
     * time its chunk was saved and reloaded. These helpers keep the exact slot of every stack.
     */
    public static ListTag writeItems(Container container) {
        ListTag list = new ListTag();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty())
                continue;
            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) i);
            stack.save(entry);
            list.add(entry);
        }
        return list;
    }

    public static void readItems(ListTag list, Container container) {
        container.clearContent();
        int size = container.getContainerSize();
        int fallback = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ItemStack stack = ItemStack.of(entry);
            if (stack.isEmpty())
                continue;
            if (entry.contains("Slot", Tag.TAG_ANY_NUMERIC)) {
                int slot = entry.getByte("Slot") & 255;
                if (slot < size)
                    container.setItem(slot, stack);
            } else {
                // Legacy data saved without slots: pack in order, as before.
                while (fallback < size && !container.getItem(fallback).isEmpty())
                    fallback++;
                if (fallback < size)
                    container.setItem(fallback, stack);
            }
        }
    }

    /** Full state of the body as stored in {@link CorpseSavedData}. */
    public CompoundTag createSnapshot() {
        CompoundTag tag = new CompoundTag();
        tag.put("Items", writeItems(items));
        tag.put("ExtraKeys", writeKeys(extraKeys));
        tag.putFloat("Health", health);
        tag.putFloat("MaxHealth", maxHealth);
        tag.put("Effects", writeEffects(storedEffects));
        tag.putInt("Regen", regenTicks);
        tag.putInt("Fire", fireResistTicks);
        tag.putInt("AbsTicks", absorptionTicks);
        tag.putFloat("Abs", absorption);
        return tag;
    }

    private static ListTag writeEffects(Collection<MobEffectInstance> effects) {
        ListTag list = new ListTag();
        for (MobEffectInstance effect : effects)
            list.add(effect.save(new CompoundTag()));
        return list;
    }

    private void readEffects(ListTag list) {
        storedEffects.clear();
        for (int i = 0; i < list.size(); i++) {
            MobEffectInstance effect = MobEffectInstance.load(list.getCompound(i));
            if (effect != null)
                storedEffects.add(effect);
        }
        syncStoredInvisibility();
    }

    public void setStoredEffects(Collection<MobEffectInstance> effects) {
        storedEffects.clear();
        for (MobEffectInstance effect : effects)
            storedEffects.add(new MobEffectInstance(effect));
        syncStoredInvisibility();
    }

    public List<MobEffectInstance> getStoredEffects() {
        List<MobEffectInstance> effects = new ArrayList<>(storedEffects.size());
        for (MobEffectInstance effect : storedEffects)
            effects.add(new MobEffectInstance(effect));
        return effects;
    }

    public boolean hasStoredInvisibility() {
        return entityData.get(STORED_INVISIBLE);
    }

    private void syncStoredInvisibility() {
        boolean invisible = false;
        for (MobEffectInstance effect : storedEffects) {
            if (effect.getEffect() == MobEffects.INVISIBILITY && effect.getDuration() > 0) {
                invisible = true;
                break;
            }
        }
        entityData.set(STORED_INVISIBLE, invisible);
    }

    public void setAbsorptionState(float amount) {
        absorption = Math.max(0.0F, amount);
    }

    /** Called right after the body is registered in the SavedData (logout). */
    public void activateSnapshot() {
        snapshotActive = true;
        pushSnapshot();
    }

    private void pushSnapshot() {
        UUID owner = getOwnerId();
        if (!snapshotActive || recovering || dying || owner == null || !(level() instanceof ServerLevel serverLevel))
            return;
        CorpseSavedData.get(serverLevel).updateSnapshot(owner, getUUID(), createSnapshot());
    }

    private void tickBookkeeping(ServerLevel serverLevel) {
        UUID owner = getOwnerId();
        if (!snapshotActive) {
            if (tickCount % 20 != 0)
                return;
            CorpseSavedData data = CorpseSavedData.get(serverLevel);
            // This body was already emptied from the snapshot while its chunk refused to load: vanish silently.
            if (data.consumeOrphan(getUUID())) {
                dying = true;
                discard();
                return;
            }
            if (owner != null && getUUID().equals(data.get(owner)))
                snapshotActive = true;
            return;
        }
        // Keep the "where is my body" record current (bodies can fall, be pushed by pistons, etc.).
        if (tickCount % 20 == 10 && owner != null)
            CorpseSavedData.get(serverLevel).updateLocation(owner, getUUID(), serverLevel.dimension(), getX(), getY(),
                    getZ(), getYRot());
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide)
            return InteractionResult.SUCCESS;
        if (dying)
            return InteractionResult.CONSUME;
        if (!CorpseConfig.ALLOW_LOOTING.get() && ownerId != null && !ownerId.equals(player.getUUID()))
            return InteractionResult.CONSUME;
        if (player.distanceToSqr(this) > 64.0D)
            return InteractionResult.CONSUME;
        List<String> keys = getExtraKeys();
        List<com.example.persistentbodies.menu.CorpseMenu.CurioSlot> looterCurios =
                com.example.persistentbodies.menu.CorpseMenu.looterCurios(player);
        NetworkHooks.openScreen((net.minecraft.server.level.ServerPlayer) player, this, buf -> {
            buf.writeVarInt(getId());
            buf.writeVarInt(keys.size());
            for (String key : keys)
                buf.writeUtf(key);
            buf.writeVarInt(looterCurios.size());
            for (var curio : looterCurios)
                buf.writeUtf(curio.key());
        });
        return InteractionResult.CONSUME;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getOwnerName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        if (player.distanceToSqr(this) > 64.0D)
            return null;
        return new com.example.persistentbodies.menu.CorpseMenu(id, inv, this);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER_NAME, "Unknown");
        entityData.define(OWNER_UUID, "");
        entityData.define(SKIN_VALUE, "");
        entityData.define(SKIN_SIGNATURE, "");
        entityData.define(CURIOS_SUPPORT, false);
        entityData.define(STORED_INVISIBLE, false);
        entityData.define(HEAD_STACK, ItemStack.EMPTY);
        entityData.define(CHEST_STACK, ItemStack.EMPTY);
        entityData.define(LEGS_STACK, ItemStack.EMPTY);
        entityData.define(FEET_STACK, ItemStack.EMPTY);
    }

    // ---- Pose / hitbox
    // -------------------------------------------------------------------------------
    /**
     * The body lies on its back along the nearest horizontal axis, so the hitbox
     * can be axis-aligned.
     */
    public float getBodyYaw() {
        return Math.round(getYRot() / 90.0F) * 90.0F;
    }

    private AABB bodyBox() {
        boolean alongX = Math.abs(Math.sin(Math.toRadians(getBodyYaw()))) > 0.5D;
        double halfX = alongX ? 1.0D : 0.45D;
        double halfZ = alongX ? 0.45D : 1.0D;
        return new AABB(getX() - halfX, getY(), getZ() - halfZ, getX() + halfX, getY() + 0.6D, getZ() + halfZ);
    }

    public boolean isHurtFlashing() {
        return hurtFlash > 0;
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == 2)
            hurtFlash = 10;
        else if (id == 35)
            playTotemEffect();
        else
            super.handleEntityEvent(id);
    }

    /** Client side: the same burst of totem particles and the same sound a living entity makes when it pops a totem. */
    private void playTotemEffect() {
        for (int i = 0; i < 30; i++) {
            level().addParticle(ParticleTypes.TOTEM_OF_UNDYING, getRandomX(0.5D), getY() + random.nextDouble() * 0.6D,
                    getRandomZ(0.5D), random.nextGaussian() * 0.2D, random.nextDouble() * 0.3D + 0.1D,
                    random.nextGaussian() * 0.2D);
        }
        level().playLocalSound(getX(), getY(), getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F, false);
    }

    // ---- Health / damage
    // -----------------------------------------------------------------------------
    public float getHealth() {
        return health;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public void setHealthState(float current, float max) {
        maxHealth = Math.max(1.0F, max);
        health = Mth.clamp(current, 0.5F, maxHealth);
    }

    private boolean isOwnerOnline() {
        UUID owner = getOwnerId();
        return owner != null && level().getServer() != null
                && level().getServer().getPlayerList().getPlayer(owner) != null;
    }

    private List<ItemStack> armorStacks() {
        List<ItemStack> list = new ArrayList<>(4);
        for (int i = 36; i <= 39; i++)
            list.add(items.getItem(i));
        return list;
    }

    private static EquipmentSlot slotForIndex(int index) {
        return switch (index) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            default -> EquipmentSlot.HEAD;
        };
    }

    private float sumArmorAttribute(Attribute attribute) {
        float total = 0.0F;
        for (int i = 36; i <= 39; i++) {
            ItemStack stack = items.getItem(i);
            if (stack.isEmpty())
                continue;
            for (AttributeModifier modifier : stack.getAttributeModifiers(slotForIndex(i)).get(attribute)) {
                if (modifier.getOperation() == AttributeModifier.Operation.ADDITION)
                    total += (float) modifier.getAmount();
            }
        }
        return total;
    }

    /**
     * Same armor + enchantment reduction a living player would get from the armor
     * stored on the body.
     */
    private float reduceDamage(DamageSource source, float damage) {
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            damage = CombatRules.getDamageAfterAbsorb(damage, sumArmorAttribute(Attributes.ARMOR),
                    sumArmorAttribute(Attributes.ARMOR_TOUGHNESS));
        }
        if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            int protection = EnchantmentHelper.getDamageProtection(armorStacks(), source);
            if (protection > 0)
                damage = CombatRules.getDamageAfterMagicAbsorb(damage, protection);
        }
        return Math.max(damage, 0.0F);
    }

    private void damageArmor(DamageSource source, float damage) {
        if (source.is(DamageTypeTags.BYPASSES_ARMOR))
            return;
        int amount = Math.max(1, (int) (damage / 4.0F));
        for (int i = 36; i <= 39; i++) {
            ItemStack stack = items.getItem(i);
            if (stack.isEmpty() || !stack.isDamageableItem())
                continue;
            if (source.is(DamageTypeTags.IS_FIRE) && stack.getItem().isFireResistant())
                continue;
            if (stack.hurt(amount, random, null)) {
                items.setItem(i, ItemStack.EMPTY);
                playSound(SoundEvents.ITEM_BREAK, 0.8F, 0.8F + random.nextFloat() * 0.4F);
            } else {
                items.setChanged();
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved() || dying || amount <= 0.0F)
            return false;
        if (isInvulnerableTo(source))
            return false;
        // The body is only a stand-in: if the owner is online (e.g. leftover body) it
        // cannot be hurt.
        if (isOwnerOnline())
            return false;
        // Fire Resistance from a popped totem.
        if (fireResistTicks > 0 && source.is(DamageTypeTags.IS_FIRE))
            return false;
        // Same rule as LivingEntity#hurt: for 20 ticks after a hit only a stronger hit goes through, and only for
        // the difference. After the first 10 ticks any damage counts again (cactus: 1 damage every 0.5 s).
        float effective = amount;
        if (hurtCooldown > 10 && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
            if (amount <= lastHurt)
                return false;
            effective = amount - lastHurt;
            lastHurt = amount;
        } else {
            lastHurt = amount;
            hurtCooldown = 20;
        }
        float damage = reduceDamage(source, effective);
        damageArmor(source, effective);
        playSound(SoundEvents.PLAYER_HURT, 1.0F, 1.0F);
        level().broadcastEntityEvent(this, (byte) 2);
        if (damage <= 0.0F)
            return false;
        // Absorption hearts soak up damage after armor, before health.
        boolean absorbedDamage = false;
        if (absorption > 0.0F) {
            float absorbed = Math.min(absorption, damage);
            absorption -= absorbed;
            damage -= absorbed;
            absorbedDamage = absorbed > 0.0F;
        }
        if (damage <= 0.0F) {
            if (absorbedDamage)
                pushSnapshot();
            return absorbedDamage;
        }
        health -= damage;
        if (health <= 0.0F && !tryUseTotem(source)) {
            die(source);
            return true;
        }
        pushSnapshot();
        return true;
    }

    /**
     * Same rules as a living player: a Totem of Undying in the main hand (the hotbar slot that was selected at
     * logout) or the offhand saves the body from any damage except the kind that bypasses invulnerability
     * (/kill, the void). A totem sitting in a Curios slot counts too and is consumed exactly like the offhand one.
     */
    private boolean tryUseTotem(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY))
            return false;
        int slot = findTotemSlot();
        if (slot < 0)
            return false;
        items.removeItem(slot, 1);
        health = 1.0F;
        regenTicks = 900;
        absorptionTicks = 100;
        absorption = 8.0F;
        fireResistTicks = 800;
        // Event 35 makes every client play the totem particles and sound at this entity (see handleEntityEvent).
        level().broadcastEntityEvent(this, (byte) 35);
        pushSnapshot();
        return true;
    }

    /** Container index of the totem that would be used: main hand, then offhand, then any Curios slot. -1 if none. */
    private int findTotemSlot() {
        int[] hands = { Mth.clamp(selectedSlot, 0, 8), 40 };
        for (int slot : hands) {
            if (items.getItem(slot).is(Items.TOTEM_OF_UNDYING))
                return slot;
        }
        for (int k = 0; k < extraKeys.size(); k++) {
            String key = extraKeys.get(k);
            if (!key.startsWith(ExtraSlots.CURIOS) && !key.startsWith(ExtraSlots.LEGACY))
                continue;
            int index = VANILLA_SLOTS + k;
            if (items.getItem(index).is(Items.TOTEM_OF_UNDYING))
                return index;
        }
        return -1;
    }

    // ---- Drowning
    // ---------------------------------------------------------------------------------------
    /** The head of the lying body is at the top of its bounding box. */
    private boolean isHeadUnderWater() {
        double headY = getBoundingBox().maxY - 0.1D;
        BlockPos pos = BlockPos.containing(getX(), headY, getZ());
        FluidState fluid = level().getFluidState(pos);
        return fluid.is(FluidTags.WATER) && pos.getY() + fluid.getHeight(level(), pos) > headY;
    }

    /** Mirrors LivingEntity#baseTick: 300 ticks of air, then 2 damage per second. Respiration slows the loss. */
    private void tickDrowning(ServerLevel serverLevel) {
        if (isHeadUnderWater()) {
            int air = getAirSupply();
            int respiration = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RESPIRATION, items.getItem(39));
            if (!(respiration > 0 && random.nextInt(respiration + 1) > 0))
                air--;
            setAirSupply(air);
            if (air == -20) {
                setAirSupply(0);
                serverLevel.sendParticles(ParticleTypes.BUBBLE, getX(), getY() + 0.3D, getZ(), 8, 0.3D, 0.1D, 0.3D,
                        0.0D);
                hurt(damageSources().drown(), 2.0F);
            }
        } else if (getAirSupply() < getMaxAirSupply()) {
            setAirSupply(Math.min(getAirSupply() + 4, getMaxAirSupply()));
        }
    }

    private void tickEffects() {
        if (hurtCooldown > 0)
            hurtCooldown--;
        tickStoredEffects();
        if (absorptionTicks > 0 && --absorptionTicks == 0)
            absorption = 0.0F;
        if (fireResistTicks > 0) {
            fireResistTicks--;
            setRemainingFireTicks(0);
        }
        if (regenTicks > 0) {
            regenTicks--;
            if (regenTicks % 25 == 0 && health < maxHealth) {
                health = Math.min(maxHealth, health + 1.0F);
                pushSnapshot();
            }
        }
    }

    private void tickStoredEffects() {
        boolean changed = false;
        for (int i = storedEffects.size() - 1; i >= 0; i--) {
            MobEffectInstance effect = storedEffects.get(i);
            int duration = effect.getDuration();
            if (duration < 0)
                continue;
            if (duration <= 1) {
                storedEffects.remove(i);
                changed = true;
                continue;
            }
            if (tickCount % 20 == 0) {
                CompoundTag effectTag = effect.save(new CompoundTag());
                effectTag.putInt("Duration", duration - 1);
                MobEffectInstance updated = MobEffectInstance.load(effectTag);
                if (updated != null) {
                    storedEffects.set(i, updated);
                    changed = true;
                }
            }
        }
        if (changed) {
            syncStoredInvisibility();
            pushSnapshot();
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        boolean result = super.causeFallDamage(distance, multiplier, source);
        if (level().isClientSide)
            return result;
        int damage = Mth.ceil((distance - 3.0F) * multiplier);
        if (damage <= 0)
            return result;
        hurt(source, (float) damage);
        return true;
    }

    /**
     * /kill and similar must behave like killing the player, not silently delete
     * the items.
     */
    @Override
    public void kill() {
        if (level().isClientSide || dying) {
            super.kill();
            return;
        }
        die(damageSources().genericKill());
    }

    /**
     * When BlockZ is installed and its corpse system is on, a body killed while its owner is offline becomes a BlockZ
     * corpse holding every item, exactly as if the player had died online. The body is emptied only after the BlockZ
     * corpse was added to the world; on any failure the items stay here and are dropped as before.
     */
    private boolean handOffToBlockZ(ServerLevel serverLevel, UUID owner, boolean ownerOnline) {
        if (owner == null || ownerOnline || !CorpseConfig.BLOCKZ_HANDOFF.get() || !BlockZBridge.isUsable()
                || !BlockZBridge.isCorpseSystemEnabled())
            return false;
        boolean created = BlockZBridge.spawnDeathCorpse(serverLevel, owner, getOwnerName(), getX(), getY(), getZ(),
                getYRot(), items, extraKeys);
        if (created)
            items.clearContent();
        return created;
    }

    private void die(DamageSource source) {
        if (dying || !(level() instanceof ServerLevel serverLevel))
            return;
        dying = true;
        health = 0.0F;
        UUID owner = getOwnerId();
        boolean ownerOnline = isOwnerOnline();

        CorpseSavedData data = CorpseSavedData.get(serverLevel);
        // keepInventory: the owner is offline and the world says dead players keep their items, so nothing is dropped.
        // The record (with its up-to-date snapshot) stays; the owner gets the items back on login and then dies.
        boolean keepForOwner = owner != null && !ownerOnline
                && serverLevel.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        if (keepForOwner) {
            data.updateSnapshot(owner, getUUID(), createSnapshot());
        } else if (!handOffToBlockZ(serverLevel, owner, ownerOnline)) {
            // Everything the body carried falls out, scattered like a dead player's inventory.
            for (int i = 0; i < items.getContainerSize(); i++) {
                ItemStack stack = items.removeItemNoUpdate(i);
                if (stack.isEmpty())
                    continue;
                ItemEntity drop = new ItemEntity(serverLevel, getX(), getY() + 0.3D, getZ(), stack);
                drop.setDeltaMovement(random.nextGaussian() * 0.1D, 0.2D + random.nextDouble() * 0.1D,
                        random.nextGaussian() * 0.1D);
                drop.setDefaultPickUpDelay();
                serverLevel.addFreshEntity(drop);
            }
        }
        playSound(SoundEvents.PLAYER_DEATH, 1.0F, 1.0F);

        if (owner != null) {
            if (!keepForOwner)
                data.remove(owner);
            Entity attacker = source.getEntity();
            String attackerName = attacker == null ? "" : attacker.getDisplayName().getString();
            if (!ownerOnline) {
                // The real player is offline: they will be killed on their next login. The marker is written to disk
                // right away (before the body disappears) so a crash cannot lose it.
                data.markDead(owner, attackerName, source.getMsgId());
                CorpseSavedData.flush(serverLevel);
                PB_LOGGER.info("Body of {} died while the owner is offline: pending death registered.", getOwnerName());
            } else {
                // Owner is online and currently downed while their (leftover) body is destroyed: finish the downed
                // player for good. The server tick processes the durable marker (with retry/backoff).
                net.minecraft.server.level.ServerPlayer online = serverLevel.getServer().getPlayerList().getPlayer(owner);
                if (online != null && DownedManager.isDowned(online.getUUID())) {
                    data.markDead(owner, attackerName, source.getMsgId());
                    CorpseSavedData.flush(serverLevel);
                    PB_LOGGER.info("Body of downed online owner {} destroyed: pending death registered.", getOwnerName());
                }
            }
        }
        if (CorpseConfig.MESSAGES.get()) {
            Component name = Component.literal(getOwnerName());
            Entity killer = source.getEntity();
            Component message;
            if (killer != null)
                message = Component.translatable("death.attack.mob", name, killer.getDisplayName());
            else
                message = Component.translatable("death.attack." + source.getMsgId(), name);
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(message, false);
        }
        discard();
    }

    @Override
    public void tick() {
        // Falling into the void counts as the body dying (vanilla would silently delete
        // it).
        if (!level().isClientSide && !dying && getY() < level().getMinBuildHeight() - 32) {
            die(damageSources().fellOutOfWorld());
            return;
        }
        if (!level().isClientSide && !dying && level() instanceof ServerLevel serverLevel) {
            tickBookkeeping(serverLevel);
            if (isRemoved())
                return;
            tickEffects();
            tickDrowning(serverLevel);
            if (dying || isRemoved())
                return;
        }
        setBoundingBox(bodyBox());
        if (hurtFlash > 0)
            hurtFlash--;
        super.tick();
        if (isNoGravity())
            setNoGravity(false);
        if (!isNoGravity() && !onGround())
            setDeltaMovement(getDeltaMovement().add(0.0D, -0.08D, 0.0D));
        move(MoverType.SELF, getDeltaMovement());
        if (onGround())
            setDeltaMovement(getDeltaMovement().x * 0.65D, 0.0D, getDeltaMovement().z * 0.65D);
        else
            setDeltaMovement(getDeltaMovement().x * 0.98D, getDeltaMovement().y * 0.98D, getDeltaMovement().z * 0.98D);
        // move() recomputes the default box from the position; restore the lying-body
        // box.
        setBoundingBox(bodyBox());
        // Client side: a "z" floats up from the head every second or so.
        if (level().isClientSide && tickCount % 24 == 0)
            spawnSleepParticle();
    }

    private void spawnSleepParticle() {
        // The head points opposite to the direction the player was facing (see the renderer).
        double rad = Math.toRadians(getBodyYaw());
        double x = getX() + Math.sin(rad) * 0.55D + (random.nextDouble() - 0.5D) * 0.12D;
        double z = getZ() - Math.cos(rad) * 0.55D + (random.nextDouble() - 0.5D) * 0.12D;
        level().addParticle(ModParticles.SLEEP_Z.get(), x, getY() + 0.6D, z, 0.0D, 0.0D, 0.0D);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner"))
            ownerId = tag.getUUID("Owner");
        entityData.set(OWNER_UUID, ownerId == null ? "" : ownerId.toString());
        entityData.set(OWNER_NAME, tag.getString("OwnerName").isEmpty() ? "Unknown" : tag.getString("OwnerName"));
        entityData.set(SKIN_VALUE, tag.getString("SkinValue"));
        entityData.set(SKIN_SIGNATURE, tag.getString("SkinSignature"));
        createdAt = tag.getLong("CreatedAt");
        recovering = tag.getBoolean("Recovering");
        readItems(tag.getList("Items", Tag.TAG_COMPOUND), items);
        extraKeys.clear();
        extraKeys.addAll(readKeys(tag));
        setCuriosSupport(!extraKeys.isEmpty());
        maxHealth = tag.contains("MaxHealth") ? Math.max(1.0F, tag.getFloat("MaxHealth")) : 20.0F;
        health = tag.contains("Health") ? tag.getFloat("Health") : maxHealth;
        selectedSlot = Mth.clamp(tag.getInt("SelectedSlot"), 0, 8);
        regenTicks = tag.getInt("Regen");
        fireResistTicks = tag.getInt("Fire");
        absorptionTicks = tag.getInt("AbsTicks");
        absorption = tag.getFloat("Abs");
        readEffects(tag.getList("Effects", Tag.TAG_COMPOUND));
        syncEquipment();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerId != null)
            tag.putUUID("Owner", ownerId);
        tag.putString("OwnerName", getOwnerName());
        tag.putString("SkinValue", getSkinTextureValue());
        tag.putString("SkinSignature", getSkinTextureSignature());
        tag.putLong("CreatedAt", createdAt);
        tag.putBoolean("Recovering", recovering);
        tag.putBoolean("CuriosSupport", hasCuriosSupport());
        tag.put("Items", writeItems(items));
        tag.put("ExtraKeys", writeKeys(extraKeys));
        tag.putFloat("Health", health);
        tag.putFloat("MaxHealth", maxHealth);
        tag.put("Effects", writeEffects(storedEffects));
        tag.putInt("SelectedSlot", selectedSlot);
        tag.putInt("Regen", regenTicks);
        tag.putInt("Fire", fireResistTicks);
        tag.putInt("AbsTicks", absorptionTicks);
        tag.putFloat("Abs", absorption);
    }

    /** The body must never go through a portal: it would come out as a different entity and break its record. */
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public int getContainerSize() {
        return items.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return items.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = items.removeItemNoUpdate(slot);
        syncEquipment();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.setItem(slot, stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        items.setChanged();
        syncEquipment();
    }

    @Override
    public boolean stillValid(Player player) {
        return isAlive() && player.distanceToSqr(this) <= 64.0D;
    }

    @Override
    public void clearContent() {
        items.clearContent();
        syncEquipment();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return true;
    }
}
