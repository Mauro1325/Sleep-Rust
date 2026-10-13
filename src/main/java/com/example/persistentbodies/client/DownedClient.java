package com.example.persistentbodies.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.persistentbodies.PersistentBodies;
import com.example.persistentbodies.network.DownedNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client presentation of the downed system: locks the local input while downed, sends the "holding the revive key"
 * heartbeat and draws the HUD. NOTHING here is authoritative: the server re-validates every condition each tick, so a
 * modified client gains nothing by skipping these checks.
 */
@Mod.EventBusSubscriber(modid = PersistentBodies.MOD_ID, value = Dist.CLIENT)
public final class DownedClient {
    /** Post-processing chain (vanilla "blur" program) applied to the downed player's own screen. */
    private static final ResourceLocation BLUR_EFFECT = new ResourceLocation(PersistentBodies.MOD_ID,
            "shaders/post/downed_blur.json");
    private static final int HEARTBEAT_EVERY = 5;
    private static final int PROGRESS_TIMEOUT = 10;

    /** downed player -> ticks left until bleed-out (-1 = no limit), counted down locally for the HUD. */
    private static final Map<UUID, Integer> DOWNED = new HashMap<>();
    private static UUID holdTarget;
    private static int ticksSinceHeartbeat;
    private static float progress = -1.0F;
    private static int progressTotalTicks;
    private static int ticksSinceProgress;
    private static UUID hintTarget;
    private static boolean blurFailed;
    private static boolean blurActive;

    private DownedClient() { }

    // ---- packets (already on the client thread) --------------------------------------------------------------------

    public static void onSync(List<DownedNetwork.DownedSyncPacket.Entry> entries) {
        DOWNED.clear();
        for (DownedNetwork.DownedSyncPacket.Entry e : entries) DOWNED.put(e.id(), e.ticksLeft());
    }

    public static void onProgress(UUID target, float value, int totalTicks) {
        progress = value;
        progressTotalTicks = totalTicks;
        ticksSinceProgress = 0;
    }

    public static boolean isDowned(UUID id) { return DOWNED.containsKey(id); }

    private static boolean meDowned(Minecraft mc) {
        return mc.player != null && DOWNED.containsKey(mc.player.getUUID());
    }

    // ---- events ------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null) { reset(); return; }

        DOWNED.replaceAll((id, left) -> left > 0 ? left - 1 : left);
        if (progress >= 0.0F && ++ticksSinceProgress > PROGRESS_TIMEOUT) progress = -1.0F;

        boolean downed = meDowned(mc);
        if (downed && mc.screen instanceof AbstractContainerScreen<?>) me.closeContainer();

        // Camera / pose fix: a downed player's pose is forced to SWIMMING (lying flat) by the server every tick, but the
        // vanilla client recomputes the pose of EVERY player each tick (STANDING / CROUCHING). The two fought each tick,
        // which made the eye height (and so the camera) jump up and down. Re-assert the lying pose after the tick.
        for (AbstractClientPlayer p : mc.level.players()) {
            if (DOWNED.containsKey(p.getUUID()) && p.getPose() != Pose.SWIMMING) p.setPose(Pose.SWIMMING);
        }
        if (downed) me.setSprinting(false);
        updateBlur(mc, downed);

        UUID candidate = downed || !me.isAlive() ? null : pickTarget(mc);
        hintTarget = candidate;
        boolean useDown = mc.options.keyUse.isDown();
        boolean wantsToRevive = candidate != null && useDown && mc.screen == null;
        if (wantsToRevive) {
            if (!candidate.equals(holdTarget) || ++ticksSinceHeartbeat >= HEARTBEAT_EVERY) {
                DownedNetwork.CHANNEL.sendToServer(new DownedNetwork.ReviveHoldPacket(candidate, true));
                ticksSinceHeartbeat = 0;
            }
            holdTarget = candidate;
        } else if (holdTarget != null && (!useDown || downed || mc.screen != null)) {
            // Explicit release only when the button is let go. Merely aiming away stops the heartbeat and the server
            // cancels after its own short grace, so a slip of the mouse does not throw away a long revive at once.
            DownedNetwork.CHANNEL.sendToServer(new DownedNetwork.ReviveHoldPacket(holdTarget, false));
            holdTarget = null;
        }
    }

    /** The downed player under the crosshair (the same ray vanilla uses to pick entities), or null. */
    private static UUID pickTarget(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof AbstractClientPlayer other
                && other != mc.player && DOWNED.containsKey(other.getUUID()))
            return other.getUUID();
        return null;
    }

    /** Own screen goes blurry while downed. Never fatal: if the effect cannot load it is simply skipped. */
    private static void updateBlur(Minecraft mc, boolean downed) {
        var renderer = mc.gameRenderer;
        if (downed) {
            if (!blurFailed && renderer.currentEffect() == null) {
                try {
                    renderer.loadEffect(BLUR_EFFECT);
                    if (renderer.currentEffect() == null) blurFailed = true; // load logged its own error
                } catch (RuntimeException e) {
                    blurFailed = true;
                }
            }
        } else {
            if (blurActive && renderer.currentEffect() != null) renderer.shutdownEffect();
            blurFailed = false;
        }
        blurActive = downed && renderer.currentEffect() != null;
    }

    /** While downed the player can only crawl: walking input stays (slowed by the server's speed modifier), jumping and sneaking go. */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!meDowned(Minecraft.getInstance())) return;
        var input = event.getInput();
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    /**
     * Attack / use / pick-block keys do nothing while downed. For a healthy player, right click on a downed player is the
     * revive key, so it must not also use the held item (eat, draw a bow, raise a shield...). Left click stays normal:
     * hitting a downed player finishes them off (the server decides).
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (meDowned(Minecraft.getInstance()) || (event.isUseItem() && hintTarget != null)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Same pose fix, inside the player's own tick, so the dimensions used by that tick are already the lying ones. */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.side.isClient() && DOWNED.containsKey(event.player.getUUID())
                && event.player.getPose() != Pose.SWIMMING)
            event.player.setPose(Pose.SWIMMING);
    }

    /** No inventory or container screens while downed (chat, pause and death screens are untouched). */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (meDowned(Minecraft.getInstance()) && event.getNewScreen() instanceof AbstractContainerScreen<?>)
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void reset() {
        if (blurActive) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameRenderer.currentEffect() != null) mc.gameRenderer.shutdownEffect();
        }
        blurActive = false;
        blurFailed = false;
        DOWNED.clear();
        holdTarget = null;
        hintTarget = null;
        progress = -1.0F;
    }

    // ---- HUD ---------------------------------------------------------------------------------------------------------

    /** Dark-red tint over the world while downed (under the HUD). */
    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !meDowned(mc)) return;
        GuiGraphics g = event.getGuiGraphics();
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x38700000);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth();
        int h = g.guiHeight();
        int y = h / 2 + 24;
        if (meDowned(mc)) {
            g.drawCenteredString(mc.font, Component.translatable("hud.persistentbodies.downed"), w / 2, y, 0xFF5555);
            Integer left = DOWNED.get(mc.player.getUUID());
            if (left != null && left >= 0)
                g.drawCenteredString(mc.font, Component.translatable("hud.persistentbodies.bleed_out", (left + 19) / 20),
                        w / 2, y + 12, 0xFFAA00);
            if (progress >= 0.0F) {
                g.drawCenteredString(mc.font, Component.translatable("hud.persistentbodies.being_revived"), w / 2, y + 24, 0x55FF55);
                bar(g, w, y + 36, progress);
                g.drawCenteredString(mc.font, remaining(), w / 2, y + 46, 0xFFFFFF);
            }
        } else if (holdTarget != null && progress >= 0.0F) {
            g.drawCenteredString(mc.font, Component.translatable("hud.persistentbodies.reviving"), w / 2, y, 0x55FF55);
            bar(g, w, y + 12, progress);
            g.drawCenteredString(mc.font, remaining(), w / 2, y + 22, 0xFFFFFF);
        } else if (hintTarget != null) {
            g.drawCenteredString(mc.font, Component.translatable("hud.persistentbodies.hint"), w / 2, y, 0xFFFFFF);
            g.drawCenteredString(mc.font, Component.translatable("hud.persistentbodies.hint_finish"), w / 2, y + 12, 0xFF7777);
        }
    }

    /** "m:ss left" of the revive in progress. */
    private static Component remaining() {
        int seconds = (int) Math.ceil((1.0F - Math.max(0.0F, Math.min(1.0F, progress))) * progressTotalTicks / 20.0F);
        return Component.translatable("hud.persistentbodies.time_left", seconds / 60, String.format("%02d", seconds % 60));
    }

    private static void bar(GuiGraphics g, int screenWidth, int y, float value) {
        int width = 100;
        int x = screenWidth / 2 - width / 2;
        g.fill(x - 1, y - 1, x + width + 1, y + 7, 0xFF000000);
        g.fill(x, y, x + width, y + 6, 0xFF404040);
        g.fill(x, y, x + Math.round(width * Math.max(0.0F, Math.min(1.0F, value))), y + 6, 0xFF55FF55);
    }
}
