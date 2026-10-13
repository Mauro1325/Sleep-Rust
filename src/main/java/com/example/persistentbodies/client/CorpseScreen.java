package com.example.persistentbodies.client;

import com.example.persistentbodies.menu.CorpseMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Corpse inventory screen. Slot backgrounds are drawn from the menu's real slot positions, so what is drawn
 * can never drift away from what is clickable. Item rendering and tooltips use vanilla's container pipeline.
 */
public class CorpseScreen extends AbstractContainerScreen<CorpseMenu> {
    private static final int TEXT = 0x404040;
    private static final int DIVIDER_Y = 134;

    public CorpseScreen(CorpseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = CorpseMenu.WIDTH;
        imageHeight = CorpseMenu.HEIGHT;
        titleLabelX = 8;
        titleLabelY = 12;
        inventoryLabelX = 8;
        inventoryLabelY = 138;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        // AbstractContainerScreen does not draw tooltips by itself: without this call hovering an item
        // shows nothing. This is vanilla's own tooltip (name, enchantments, attributes, durability, mods).
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        drawPanel(g, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        int x0 = leftPos + CorpseMenu.WIDTH + 2;
        if (extrasVisible()) {
            drawPanel(g, x0, topPos, x0 + extraPanelWidth(), topPos + extraPanelHeight());
        }
        if (looterExtrasVisible()) {
            int y0 = topPos + CorpseMenu.LOOTER_EXTRA_PANEL_Y;
            drawPanel(g, x0, y0, x0 + looterPanelWidth(), y0 + looterPanelHeight());
        }
        // Separator between the body's inventory and the looter's own.
        g.fill(leftPos + 8, topPos + DIVIDER_Y, leftPos + imageWidth - 8, topPos + DIVIDER_Y + 1, 0xFF8B8B8B);

        for (Slot slot : menu.slots) {
            if (slot.isActive()) drawSlot(g, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
    }

    private boolean extrasVisible() {
        return menu.getExtraCount() > 0;
    }

    private boolean looterExtrasVisible() {
        return menu.getLooterCuriosCount() > 0;
    }

    private int looterPanelWidth() {
        return menu.getLooterCuriosColumns() * 18 + 12;
    }

    private int looterPanelHeight() {
        return (CorpseMenu.LOOTER_EXTRA_Y - CorpseMenu.LOOTER_EXTRA_PANEL_Y) + menu.getLooterCuriosRows() * 18 + 6;
    }

    private int extraPanelWidth() {
        return menu.getExtraColumns() * 18 + 12;
    }

    private int extraPanelHeight() {
        return CorpseMenu.CORPSE_Y + menu.getExtraRows() * 18 + 6;
    }

    /** Vanilla-style beveled panel. */
    private static void drawPanel(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, 0xFF000000);
        g.fill(x0 + 1, y0 + 1, x1 - 2, y1 - 2, 0xFFFFFFFF);
        g.fill(x0 + 2, y0 + 2, x1 - 1, y1 - 1, 0xFF555555);
        g.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0xFFC6C6C6);
    }

    /** Vanilla-style 18x18 slot (x, y is the top-left corner of the frame). */
    private static void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF373737);
        g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.literal("Inventario de " + menu.getCorpseEntity().getOwnerName()), titleLabelX, titleLabelY, TEXT, false);
        g.drawString(font, Component.literal("Equipo"), 8, 30, TEXT, false);
        g.drawString(font, Component.literal("Inventario"), CorpseMenu.GRID_X, 30, TEXT, false);
        g.drawString(font, Component.literal("Tu inventario"), inventoryLabelX, inventoryLabelY, TEXT, false);
        if (extrasVisible()) {
            g.drawString(font, Component.literal("Accesorios"), CorpseMenu.WIDTH + 8, 30, TEXT, false);
        }
        if (looterExtrasVisible()) {
            g.drawString(font, Component.literal("Tus accesorios"), CorpseMenu.WIDTH + 8, CorpseMenu.LOOTER_EXTRA_PANEL_Y + 6, TEXT, false);
        }
    }

    /** Clicks on the side panels are inside the GUI, not "outside" (which would drop the carried item). */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        boolean outside = mouseX < left || mouseY < top || mouseX >= left + imageWidth || mouseY >= top + imageHeight;
        if (!outside) return false;
        int x0 = left + CorpseMenu.WIDTH + 2;
        if (extrasVisible() && inRect(mouseX, mouseY, x0, top, extraPanelWidth(), extraPanelHeight())) return false;
        if (looterExtrasVisible() && inRect(mouseX, mouseY, x0, top + CorpseMenu.LOOTER_EXTRA_PANEL_Y, looterPanelWidth(), looterPanelHeight())) return false;
        return true;
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
