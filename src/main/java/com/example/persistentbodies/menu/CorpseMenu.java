package com.example.persistentbodies.menu;

import com.example.persistentbodies.curios.CuriosBridge;
import com.example.persistentbodies.entity.PlayerCorpseEntity;
import com.example.persistentbodies.registry.ModMenus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Loot menu. Menu slots: 0-35 body inventory, 36-40 body equipment, then one slot per "extra" the body holds
 * (Curios accessories, BlockZ backpack/vest/gloves/mask), then the looter's inventory and armor.
 * Extra slots are take-only: what a body may hold there was decided when its owner disconnected.
 */
public class CorpseMenu extends AbstractContainerMenu {
    public static final int VANILLA_SLOTS = PlayerCorpseEntity.VANILLA_SLOTS; // 0-35 inventory, 36-39 armor, 40 offhand
    // Screen layout (all coordinates are relative to the top-left corner of the GUI).
    public static final int WIDTH = 228;
    public static final int HEIGHT = 246;
    public static final int EQUIP_X = 16;    // equipment column ("Su equipo" / looter armor)
    public static final int GRID_X = 58;     // 9x4 inventory grids ("Su inventario" / "Tu inventario")
    public static final int CORPSE_Y = 40;   // first row of the corpse section
    public static final int PLAYER_Y = 150;  // first row of the looter's section
    public static final int EXTRA_X = WIDTH + 8;
    /** Extra-slot panels never grow taller than this many rows; extra slots spill into more columns instead. */
    public static final int EXTRA_MAX_ROWS = 5;
    public static final int EXTRA_MIN_COLUMNS = 4;
    /** First row of the looter's own accessories (display only), in the right panel next to the looter's inventory. */
    public static final int LOOTER_EXTRA_Y = 158;
    public static final int LOOTER_EXTRA_PANEL_Y = LOOTER_EXTRA_Y - 18;
    /** Upper bound of the looter's accessory slots shown for comparison. */
    public static final int MAX_LOOTER_CURIOS = 40;
    private final int playerStart;      // first looter slot in the menu
    private final int playerInvEnd;     // main inventory + hotbar of the looter
    private final List<String> extraKeys;
    private final int extraColumns;
    private final Container corpse;
    private final PlayerCorpseEntity corpseEntity;
    private final Inventory looterInventory;
    private final Container looterCurios;
    private final int looterCuriosCount;
    private final int looterCuriosColumns;
    private final int looterEquipEnd;   // end of the looter's equipment slots; display-only slots come after

    /** Client side: the server sends the entity id followed by the keys of the extra slots. */
    public CorpseMenu(int id, Inventory playerInventory, FriendlyByteBuf data) {
        this(id, playerInventory, getEntity(playerInventory, data), readKeys(data), readLooterCurios(data));
    }

    /** The server tells the client how many accessory slots the looter has; the client's copies start empty and are filled by the menu sync. */
    private static List<ItemStack> readLooterCurios(FriendlyByteBuf data) {
        int count = Math.min(data.readVarInt(), MAX_LOOTER_CURIOS);
        List<ItemStack> stacks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) stacks.add(ItemStack.EMPTY);
        return stacks;
    }

    /** The looter's Curios slots (empty ones too), copied. Server side; the same list sizes the client menu. */
    public static List<ItemStack> looterCurios(Player player) {
        List<ItemStack> stacks = new ArrayList<>();
        for (CuriosBridge.SlotRef ref : CuriosBridge.listSlots(player)) {
            if (stacks.size() >= MAX_LOOTER_CURIOS) break;
            stacks.add(ref.stack().copy());
        }
        return stacks;
    }

    private static List<String> readKeys(FriendlyByteBuf data) {
        int count = Math.min(data.readVarInt(), PlayerCorpseEntity.MAX_EXTRAS);
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) keys.add(data.readUtf());
        return keys;
    }

    private static PlayerCorpseEntity getEntity(Inventory inventory, FriendlyByteBuf data) {
        int entityId = data.readVarInt();
        if (inventory.player.level().getEntity(entityId) instanceof PlayerCorpseEntity corpse) return corpse;
        throw new IllegalStateException("Corpse entity is not present on the client");
    }

    /** Server side. */
    public CorpseMenu(int id, Inventory playerInventory, PlayerCorpseEntity entity) {
        this(id, playerInventory, entity, entity.getExtraKeys(), looterCurios(playerInventory.player));
    }

    private CorpseMenu(int id, Inventory playerInventory, PlayerCorpseEntity entity, List<String> keys, List<ItemStack> looterStacks) {
        super(ModMenus.CORPSE_MENU.get(), id);
        this.corpseEntity = entity;
        this.looterInventory = playerInventory;
        this.corpse = entity;
        this.extraKeys = List.copyOf(keys);
        this.extraColumns = columnsFor(extraKeys.size());
        this.playerStart = VANILLA_SLOTS + extraKeys.size();
        this.playerInvEnd = playerStart + 36;
        this.looterEquipEnd = playerInvEnd + 5; // 4 armor pieces + offhand
        this.looterCuriosCount = looterStacks.size();
        this.looterCuriosColumns = columnsFor(looterCuriosCount);
        SimpleContainer display = new SimpleContainer(Math.max(1, looterCuriosCount));
        for (int i = 0; i < looterCuriosCount; i++) display.setItem(i, looterStacks.get(i));
        this.looterCurios = display;
        checkContainerSize(corpse, VANILLA_SLOTS + extraKeys.size());
        corpse.startOpen(playerInventory.player);

        // Menu slot order is part of the shift-click logic: 0-35 body inventory, 36-40 body equipment,
        // then the extra slots, then the looter's inventory (36 slots) and finally the looter's equipment.

        // Body inventory, vanilla layout: three rows of storage with the hotbar underneath.
        for (int i = 0; i < 36; i++) {
            addSlot(new LootSlot(corpse, i, GRID_X + (i % 9) * 18, gridY(CORPSE_Y, i)));
        }
        // Body equipment: helmet, chestplate, leggings, boots, offhand (one column, same order as vanilla).
        addSlot(new LootSlot(corpse, 39, EQUIP_X, CORPSE_Y).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET));
        addSlot(new LootSlot(corpse, 38, EQUIP_X, CORPSE_Y + 18).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE));
        addSlot(new LootSlot(corpse, 37, EQUIP_X, CORPSE_Y + 36).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS));
        addSlot(new LootSlot(corpse, 36, EQUIP_X, CORPSE_Y + 54).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS));
        addSlot(new LootSlot(corpse, 40, EQUIP_X, CORPSE_Y + 72).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));
        // Extra slots (Curios, BlockZ equipment) live in their own side panel, one slot per extra the body holds.
        for (int i = 0; i < extraKeys.size(); i++) {
            addSlot(new LootSlot(corpse, VANILLA_SLOTS + i, EXTRA_X + (i % extraColumns) * 18, CORPSE_Y + (i / extraColumns) * 18));
        }

        // The looter's own inventory, in exactly the same layout, right below.
        for (int i = 0; i < 36; i++) {
            addSlot(new Slot(playerInventory, i, GRID_X + (i % 9) * 18, gridY(PLAYER_Y, i)));
        }
        // Looter's equipment. Player Inventory indices: 39=head, 38=chest, 37=legs, 36=feet, 40=offhand.
        addSlot(new PlayerArmorSlot(playerInventory, 39, EquipmentSlot.HEAD, EQUIP_X, PLAYER_Y).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET));
        addSlot(new PlayerArmorSlot(playerInventory, 38, EquipmentSlot.CHEST, EQUIP_X, PLAYER_Y + 18).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE));
        addSlot(new PlayerArmorSlot(playerInventory, 37, EquipmentSlot.LEGS, EQUIP_X, PLAYER_Y + 36).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS));
        addSlot(new PlayerArmorSlot(playerInventory, 36, EquipmentSlot.FEET, EQUIP_X, PLAYER_Y + 54).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS));
        // Looter's offhand, right under their armor, mirroring the body's equipment column.
        addSlot(new Slot(playerInventory, 40, EQUIP_X, PLAYER_Y + 72).setBackground(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));
        // Looter's own accessories, shown (read only) in the right panel so they can be compared with the body's.
        for (int i = 0; i < looterCuriosCount; i++) {
            addSlot(new DisplaySlot(looterCurios, i, EXTRA_X + (i % looterCuriosColumns) * 18, LOOTER_EXTRA_Y + (i / looterCuriosColumns) * 18));
        }
    }

    private static int columnsFor(int count) {
        return Math.max(EXTRA_MIN_COLUMNS, (count + EXTRA_MAX_ROWS - 1) / EXTRA_MAX_ROWS);
    }

    /** 9x4 grid like the reference layout: inventory index 9-35 are rows 1-3, 0-8 (hotbar) is the 4th row. */
    private static int gridY(int top, int index) {
        if (index < 9) return top + 54;                  // hotbar row
        return top + ((index - 9) / 9) * 18;             // main rows
    }

    public PlayerCorpseEntity getCorpseEntity() { return corpseEntity; }
    public int getExtraCount() { return extraKeys.size(); }
    public int getExtraColumns() { return extraColumns; }
    public int getExtraRows() { return extraKeys.isEmpty() ? 0 : (extraKeys.size() + extraColumns - 1) / extraColumns; }
    public int getLooterCuriosCount() { return looterCuriosCount; }
    public int getLooterCuriosColumns() { return looterCuriosColumns; }
    public int getLooterCuriosRows() { return looterCuriosCount == 0 ? 0 : (looterCuriosCount + looterCuriosColumns - 1) / looterCuriosColumns; }

    @Override public boolean stillValid(Player player) {
        return corpseEntity.isAlive() && corpseEntity.distanceToSqr(player) <= 64.0D;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < playerStart) {
            // Body -> looter's inventory (never straight into the looter's armor slots).
            if (!moveItemStackTo(stack, playerStart, playerInvEnd, true)) return ItemStack.EMPTY;
        } else if (index < playerInvEnd) {
            // Looter's inventory -> body's general inventory. Equipment slots can still be filled directly
            // with normal clicks; extra slots are take-only.
            if (!moveItemStackTo(stack, 0, 36, false)) return ItemStack.EMPTY;
        } else if (index < looterEquipEnd) {
            // Looter's equipment -> looter's inventory.
            if (!moveItemStackTo(stack, playerStart, playerInvEnd, false)) return ItemStack.EMPTY;
        } else {
            // Display-only accessory slots of the looter: nothing to move.
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        return copy;
    }

    @Override public void removed(Player player) {
        super.removed(player);
        corpse.stopOpen(player);
    }


    /** A slot that only shows an item: nothing can be taken from it or put into it. */
    private static final class DisplaySlot extends Slot {
        DisplaySlot(Container container, int index, int x, int y) { super(container, index, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    private static final class PlayerArmorSlot extends Slot {
        private final EquipmentSlot expected;
        PlayerArmorSlot(Inventory inventory, int index, EquipmentSlot expected, int x, int y) {
            super(inventory, index, x, y);
            this.expected = expected;
        }
        @Override public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == expected;
        }
    }

    private final class LootSlot extends Slot {
        private final int bodyIndex;
        LootSlot(Container container, int index, int x, int y) { super(container, index, x, y); bodyIndex = index; }
        @Override public boolean mayPlace(ItemStack stack) {
            if (bodyIndex >= 36 && bodyIndex <= 39) {
                if (!(stack.getItem() instanceof ArmorItem armor)) return false;
                EquipmentSlot expected = switch (bodyIndex) {
                    case 36 -> EquipmentSlot.FEET;
                    case 37 -> EquipmentSlot.LEGS;
                    case 38 -> EquipmentSlot.CHEST;
                    case 39 -> EquipmentSlot.HEAD;
                    default -> EquipmentSlot.MAINHAND;
                };
                return armor.getEquipmentSlot() == expected;
            }
            // Extra slots are take-only: they are restored into the owner's matching Curios/BlockZ slot by key,
            // so putting arbitrary items there could never be restored correctly.
            return bodyIndex < VANILLA_SLOTS;
        }
    }
}
