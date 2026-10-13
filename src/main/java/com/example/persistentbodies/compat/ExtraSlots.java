package com.example.persistentbodies.compat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;
import com.example.persistentbodies.curios.CuriosBridge;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * "Extra" slots are everything a player carries outside the 41 vanilla inventory slots: every Curios slot and the
 * four BlockZ capability slots (backpack, vest, gloves, mask). A body stores each one under a stable key:
 *
 * <pre>
 *   curios:&lt;identifier&gt;:&lt;index&gt;   e.g. curios:ring:1
 *   blockz:&lt;0-3&gt;                  0 backpack, 1 vest, 2 gloves, 3 mask
 *   legacy:&lt;n&gt;                    Curios of bodies saved before keys existed (position in the combined handler)
 * </pre>
 *
 * The key of the k-th extra is stored in the body's key list and its stack lives at container index
 * {@link #FIRST_EXTRA_SLOT} + k.
 */
public final class ExtraSlots {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String CURIOS = "curios:";
    public static final String BLOCKZ = "blockz:";
    public static final String LEGACY = "legacy:";
    /** Container index of the first extra slot (after 36 inventory + 4 armor + 1 offhand). */
    public static final int FIRST_EXTRA_SLOT = 41;

    private ExtraSlots() { }

    /**
     * Offers every non-empty extra stack of the player to {@code sink} (key, copy of the stack; return false when the
     * body is full). Nothing is removed from the player here.
     *
     * @return the actions that remove the accepted stacks from the player. Run them only after the body exists, so a
     *         failure can never lose items.
     */
    public static List<Runnable> collect(ServerPlayer player, BiPredicate<String, ItemStack> sink) {
        List<Runnable> removals = new ArrayList<>();
        List<CuriosBridge.SlotRef> curios = new ArrayList<>(CuriosBridge.listSlots(player));

        if (BlockZBridge.isUsable()) {
            for (int slot = 0; slot < BlockZBridge.CAP_SLOTS; slot++) {
                ItemStack stack = BlockZBridge.getSlot(player, slot);
                if (stack.isEmpty()) continue;
                if (!sink.test(BLOCKZ + slot, stack.copy())) continue;
                final int capSlot = slot;
                removals.add(() -> BlockZBridge.setSlot(player, capSlot, ItemStack.EMPTY));
                // BlockZ keeps a copy of back/body/head Curios items in these slots. Storing both would hand the
                // same backpack out twice, so the Curios copy is dropped when it is the same item.
                String mirror = BlockZBridge.mirroredCurioId(slot);
                if (mirror == null) continue;
                Iterator<CuriosBridge.SlotRef> it = curios.iterator();
                while (it.hasNext()) {
                    CuriosBridge.SlotRef ref = it.next();
                    if (ref.index() == 0 && ref.identifier().equals(mirror)
                            && ItemStack.isSameItemSameTags(ref.stack(), stack)) {
                        removals.add(() -> ref.handler().setStackInSlot(ref.index(), ItemStack.EMPTY));
                        it.remove();
                        break;
                    }
                }
            }
        }

        int curiosWithItems = 0;
        int curiosMoved = 0;
        for (CuriosBridge.SlotRef ref : curios) {
            ItemStack stack = ref.stack();
            if (stack.isEmpty()) continue;
            curiosWithItems++;
            if (!sink.test(CURIOS + ref.identifier() + ":" + ref.index(), stack.copy())) continue;
            curiosMoved++;
            removals.add(() -> ref.handler().setStackInSlot(ref.index(), ItemStack.EMPTY));
        }
        // Diagnostics: if a player says "my accessories are not on the body", this line tells where they got lost.
        LOGGER.info("Body of {}: Curios loaded={}, {} Curios slots listed, {} with items, {} moved to the body.",
                player.getGameProfile().getName(), CuriosBridge.isLoaded(), curios.size(), curiosWithItems, curiosMoved);
        return removals;
    }

    /** Runs the removals returned by {@link #collect}; one failing removal does not stop the others. */
    public static void runAll(List<Runnable> removals) {
        for (Runnable removal : removals) {
            try {
                removal.run();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("Could not remove an item that was moved to the body; it may now exist twice.", e);
            }
        }
    }

    /** Puts a stack back where the key says it came from. @return what did not fit (the whole stack, or EMPTY) */
    public static ItemStack restore(ServerPlayer player, String key, ItemStack stack) {
        try {
            if (key.startsWith(BLOCKZ)) {
                return BlockZBridge.restoreSlot(player, Integer.parseInt(key.substring(BLOCKZ.length())), stack);
            }
            if (key.startsWith(CURIOS)) {
                int split = key.lastIndexOf(':');
                String identifier = key.substring(CURIOS.length(), split);
                return CuriosBridge.restore(player, identifier, Integer.parseInt(key.substring(split + 1)), stack);
            }
            if (key.startsWith(LEGACY)) {
                return CuriosBridge.restoreLegacy(player, Integer.parseInt(key.substring(LEGACY.length())), stack);
            }
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            LOGGER.warn("Ignoring malformed body slot key '{}'", key);
        }
        return stack;
    }
}
