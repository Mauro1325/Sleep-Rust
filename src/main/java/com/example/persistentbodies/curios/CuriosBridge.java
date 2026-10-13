package com.example.persistentbodies.curios;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;

/**
 * Optional Curios bridge. Everything goes through reflection so the mod still loads (and nothing is required at
 * compile time) when Curios is not installed.
 *
 * Slots are addressed by {@code identifier + index} (e.g. "ring", 1), exactly as Curios itself does, instead of by
 * their position in the combined "equipped curios" handler. That position changes whenever another mod adds, removes
 * or reorders a slot type, which used to put restored items in the wrong slot.
 */
public final class CuriosBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String HANDLER = "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
    private static final String STACKS = "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler";

    private static boolean resolved;
    private static boolean available;
    /** CuriosApi#getCuriosInventory(LivingEntity): LazyOptional on Forge 1.20.1, Optional on newer versions. May be null. */
    private static Method getInventory;
    /** CuriosCapability.INVENTORY, used when the API method is missing or returns nothing. May be null. */
    private static Capability<?> inventoryCapability;
    private static Class<?> handlerClass;
    private static Method getCurios;
    private static Method getEquipped;
    private static Method getStacks;
    private static boolean warnedNoHandler;

    private CuriosBridge() { }

    /** One concrete Curios slot of one entity. */
    public record SlotRef(String identifier, int index, IItemHandlerModifiable handler) {
        public ItemStack stack() {
            return handler.getStackInSlot(index);
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) return available;
        resolved = true;
        try {
            if (!ModList.get().isLoaded("curios")) return false;
            handlerClass = Class.forName(HANDLER);
            getCurios = handlerClass.getMethod("getCurios");
            getStacks = Class.forName(STACKS).getMethod("getStacks");
            try {
                getEquipped = handlerClass.getMethod("getEquippedCurios");
            } catch (NoSuchMethodException e) {
                getEquipped = null; // only needed for bodies saved by very old versions
            }
            // Two independent ways to reach the handler: the API method and the raw capability. Either is enough.
            Class<?> api = Class.forName(API);
            try {
                getInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
            } catch (NoSuchMethodException e) {
                getInventory = null;
            }
            try {
                Object cap = Class.forName("top.theillusivec4.curios.api.CuriosCapability").getField("INVENTORY").get(null);
                if (cap instanceof Capability<?> capability) inventoryCapability = capability;
            } catch (ReflectiveOperationException e) {
                inventoryCapability = null;
            }
            available = getInventory != null || inventoryCapability != null;
            if (!available) LOGGER.warn("Curios is installed but neither CuriosApi#getCuriosInventory nor CuriosCapability.INVENTORY was found; Curios slots will be ignored.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Curios is installed but its API could not be read; Curios slots will be ignored.", e);
        }
        return available;
    }

    public static boolean isLoaded() {
        return resolve();
    }

    /** Accepts everything Curios has returned over time: LazyOptional, Optional or the handler itself. */
    private static Object unwrap(Object result) {
        if (result instanceof LazyOptional<?> lazy) return lazy.orElse(null);
        if (result instanceof Optional<?> optional) return optional.orElse(null);
        return handlerClass.isInstance(result) ? result : null;
    }

    private static Object handlerOf(LivingEntity entity) {
        if (!resolve()) return null;
        Object handler = null;
        if (getInventory != null) {
            try {
                handler = unwrap(getInventory.invoke(null, entity));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                handler = null;
            }
        }
        if (handler == null && inventoryCapability != null) {
            try {
                handler = unwrap(entity.getCapability(inventoryCapability));
            } catch (LinkageError | RuntimeException e) {
                handler = null;
            }
        }
        if (handler == null && !warnedNoHandler) {
            warnedNoHandler = true;
            LOGGER.warn("Curios is loaded but {} has no Curios inventory handler; its Curios slots cannot be read.",
                    entity.getName().getString());
        }
        return handler;
    }

    /** Every Curios slot of the entity (empty ones too), sorted by identifier and index. */
    public static List<SlotRef> listSlots(LivingEntity entity) {
        Object handler = handlerOf(entity);
        if (handler == null) return List.of();
        try {
            if (!(getCurios.invoke(handler) instanceof Map<?, ?> map)) return List.of();
            List<String> identifiers = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (key instanceof String id) identifiers.add(id);
            }
            Collections.sort(identifiers);
            List<SlotRef> slots = new ArrayList<>();
            for (String id : identifiers) {
                Object stacksHandler = map.get(id);
                if (stacksHandler == null) continue;
                if (getStacks.invoke(stacksHandler) instanceof IItemHandlerModifiable modifiable) {
                    for (int i = 0; i < modifiable.getSlots(); i++) slots.add(new SlotRef(id, i, modifiable));
                }
            }
            return slots;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Could not list the Curios slots of {}", entity.getName().getString(), e);
            return List.of();
        }
    }

    /**
     * Puts a stack back into the slot it came from. If that exact slot is taken (or no longer exists) any other
     * empty slot of the same identifier is used.
     *
     * @return what could not be placed (the whole stack, or EMPTY)
     */
    public static ItemStack restore(LivingEntity entity, String identifier, int index, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        List<SlotRef> sameType = new ArrayList<>();
        for (SlotRef ref : listSlots(entity)) {
            if (ref.identifier().equals(identifier)) sameType.add(ref);
        }
        for (SlotRef ref : sameType) {
            if (ref.index() == index && ref.stack().isEmpty()) {
                ref.handler().setStackInSlot(ref.index(), stack.copy());
                return ItemStack.EMPTY;
            }
        }
        for (SlotRef ref : sameType) {
            if (ref.stack().isEmpty()) {
                ref.handler().setStackInSlot(ref.index(), stack.copy());
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    /**
     * Bodies saved by older versions stored Curios as "the N-th slot of the combined equipped-curios handler".
     * Kept only so those bodies can still be returned to their owner.
     */
    public static ItemStack restoreLegacy(LivingEntity entity, int position, ItemStack stack) {
        Object handler = handlerOf(entity);
        if (handler == null || stack.isEmpty()) return stack;
        try {
            if (getEquipped != null && getEquipped.invoke(handler) instanceof IItemHandlerModifiable combined && position >= 0
                    && position < combined.getSlots() && combined.getStackInSlot(position).isEmpty()) {
                combined.setStackInSlot(position, stack.copy());
                return ItemStack.EMPTY;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // fall through: the caller moves the stack to the inventory
        }
        return stack;
    }
}
