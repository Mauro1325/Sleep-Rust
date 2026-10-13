package com.example.persistentbodies.client;

import com.example.persistentbodies.entity.PlayerCorpseEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.resources.DefaultPlayerSkin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/** Renders a vanilla player model lying down, using the saved authenticated skin when available. */
public class PlayerCorpseRenderer extends EntityRenderer<PlayerCorpseEntity> {
    private final PlayerCorpseModel model;
    private final PlayerCorpseModel slimModel;
    private final PlayerCorpseModel capeModel;
    private final PlayerCorpseModel armorModel;
    private final PlayerCorpseModel slimArmorModel;
    private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();

    public PlayerCorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.model = new PlayerCorpseModel(context.bakeLayer(PlayerCorpseModel.LAYER_LOCATION));
        this.slimModel = new PlayerCorpseModel(context.bakeLayer(PlayerCorpseModel.SLIM_LAYER_LOCATION));
        this.capeModel = new PlayerCorpseModel(context.bakeLayer(PlayerCorpseModel.CAPE_LAYER_LOCATION));
        this.armorModel = new PlayerCorpseModel(context.bakeLayer(PlayerCorpseModel.ARMOR_LAYER_LOCATION));
        this.slimArmorModel = new PlayerCorpseModel(context.bakeLayer(PlayerCorpseModel.SLIM_ARMOR_LAYER_LOCATION));
        this.shadowRadius = 0.35F;
    }

    @Override
    public void render(PlayerCorpseEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        ResourceLocation skin = getTextureLocation(entity);
        poseStack.pushPose();
        // Lying on its back, face up: rotating the (y-down) model 90 degrees around X puts the face on top.
        // The head points opposite to the direction the player was facing; the model is shifted so it is
        // centered inside the (symmetric) hitbox.
        poseStack.translate(0.0D, 0.30D, 0.0D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.getBodyYaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        poseStack.translate(0.0D, -0.125D, 0.0D);
        // Breathing: the chest rises and falls a tiny bit.
        float age = entity.tickCount + partialTick;
        float breath = (float) Math.sin(age * 0.08F);
        poseStack.scale(1.0F + 0.006F * breath, 1.0F + 0.006F * breath, 1.0F + 0.025F * breath);
        int overlay = entity.isHurtFlashing()
                ? OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(true))
                : OverlayTexture.NO_OVERLAY;

        PlayerCorpseModel selectedModel = isSlim(null, entity) ? slimModel : model;
        selectedModel.applySleep(age);

        // A player's cape is a separate texture entry in the authenticated textures property.
        ResourceLocation cape = getCapeTextureLocation(entity);
        if (cape != null) {
            capeModel.renderCape(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(cape)), packedLight, overlay);
        }

        // Base skin plus all four second-layer regions (hat, jacket, sleeves and pants).
        selectedModel.renderToBuffer(poseStack, buffer.getBuffer(RenderType.entityCutoutNoCull(skin)),
                packedLight, overlay, 1.0F, 1.0F, 1.0F, 1.0F);

        // Render worn armor as armor geometry on the prone model, not as loose inventory icons.
        // The armor comes from the entity's synched data, so it updates live for every nearby player
        // whenever someone takes a piece off the body (or puts one on).
        renderArmor(entity, EquipmentSlot.HEAD, poseStack, buffer, packedLight, overlay, age);
        renderArmor(entity, EquipmentSlot.CHEST, poseStack, buffer, packedLight, overlay, age);
        renderArmor(entity, EquipmentSlot.LEGS, poseStack, buffer, packedLight, overlay, age);
        renderArmor(entity, EquipmentSlot.FEET, poseStack, buffer, packedLight, overlay, age);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    /** The player's name floats over the body only while you are looking at it. */
    @Override
    protected boolean shouldShowName(PlayerCorpseEntity entity) {
        return Minecraft.getInstance().crosshairPickEntity == entity && !entity.getOwnerName().isEmpty();
    }

    private void renderArmor(PlayerCorpseEntity entity, EquipmentSlot slot,
                             PoseStack poseStack, MultiBufferSource buffer, int packedLight, int overlay, float age) {
        ItemStack stack = entity.getArmorStack(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) return;
        try {
            PlayerCorpseModel selected = isSlim(null, entity) ? slimArmorModel : armorModel;
            selected.applySleep(age);
            boolean glint = stack.hasFoil();
            float r = 1.0F, g = 1.0F, b = 1.0F;
            boolean dyeable = armor instanceof DyeableLeatherItem;
            if (dyeable) {
                int color = ((DyeableLeatherItem) armor).getColor(stack);
                r = (color >> 16 & 255) / 255.0F;
                g = (color >> 8 & 255) / 255.0F;
                b = (color & 255) / 255.0F;
            }
            ResourceLocation texture = armorTexture(armor, slot, null);
            selected.renderArmor(slot, poseStack,
                    ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(texture), false, glint),
                    packedLight, overlay, r, g, b);
            if (dyeable) {
                ResourceLocation overlayTexture = armorTexture(armor, slot, "overlay");
                if (Minecraft.getInstance().getResourceManager().getResource(overlayTexture).isPresent()) {
                    selected.renderArmor(slot, poseStack,
                            ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(overlayTexture), false, glint),
                            packedLight, overlay, 1.0F, 1.0F, 1.0F);
                }
            }
        } catch (RuntimeException exception) {
            // Keep the corpse visible if an unusual modded armor material uses a custom path.
        }
    }

    /** Armor textures follow the vanilla convention, e.g. minecraft:textures/models/armor/diamond_layer_1.png. */
    private static ResourceLocation armorTexture(ArmorItem armor, EquipmentSlot slot, String suffix) {
        String materialName = armor.getMaterial().getName();
        String namespace = "minecraft";
        String path = materialName;
        int separator = materialName.indexOf(':');
        if (separator >= 0) {
            namespace = materialName.substring(0, separator);
            path = materialName.substring(separator + 1);
        }
        int layer = slot == EquipmentSlot.LEGS ? 2 : 1;
        return new ResourceLocation(namespace, "textures/models/armor/" + path + "_layer_" + layer + (suffix == null ? "" : "_" + suffix) + ".png");
    }

    /** Skin, cape and model type decoded once from the saved (Base64) "textures" property. */
    private record SkinData(ResourceLocation skin, ResourceLocation cape, boolean slim) { }

    private SkinData skinData(PlayerCorpseEntity entity) {
        String value = entity.getSkinTextureValue();
        UUID owner = entity.getOwnerId() == null ? entity.getUUID() : entity.getOwnerId();
        if (value == null || value.isEmpty()) return new SkinData(DefaultPlayerSkin.getDefaultSkin(owner), null, false);
        return skinCache.computeIfAbsent(value, ignored -> loadSkinData(value, owner));
    }

    private static SkinData loadSkinData(String value, UUID owner) {
        ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin(owner);
        ResourceLocation cape = null;
        boolean slim = false;
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonObject textures = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
            var skinManager = Minecraft.getInstance().getSkinManager();
            if (textures != null && textures.has("SKIN")) {
                JsonObject skinJson = textures.getAsJsonObject("SKIN");
                Map<String, String> metadata = new HashMap<>();
                if (skinJson.has("metadata") && skinJson.getAsJsonObject("metadata").has("model")) {
                    String model = skinJson.getAsJsonObject("metadata").get("model").getAsString();
                    metadata.put("model", model);
                    slim = "slim".equals(model);
                }
                skin = skinManager.registerTexture(new MinecraftProfileTexture(skinJson.get("url").getAsString(), metadata), MinecraftProfileTexture.Type.SKIN);
            }
            if (textures != null && textures.has("CAPE")) {
                cape = skinManager.registerTexture(new MinecraftProfileTexture(textures.getAsJsonObject("CAPE").get("url").getAsString(), null), MinecraftProfileTexture.Type.CAPE);
            }
        } catch (RuntimeException exception) {
            // Malformed property: fall back to the default skin so the corpse is still visible.
        }
        return new SkinData(skin, cape, slim);
    }

    private ResourceLocation getCapeTextureLocation(PlayerCorpseEntity entity) {
        return skinData(entity).cape();
    }

    private boolean isSlim(String ignored, PlayerCorpseEntity entity) {
        return skinData(entity).slim();
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerCorpseEntity entity) {
        return skinData(entity).skin();
    }
}
