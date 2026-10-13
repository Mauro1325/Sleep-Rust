package com.example.persistentbodies.client;

import com.example.persistentbodies.entity.PlayerCorpseEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

/** Player-shaped prone body with vanilla skin overlay layers and armor geometry. */
public class PlayerCorpseModel extends EntityModel<PlayerCorpseEntity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("persistentbodies", "player_corpse"), "main");
    public static final ModelLayerLocation SLIM_LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("persistentbodies", "player_corpse_slim"), "main");
    public static final ModelLayerLocation CAPE_LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("persistentbodies", "player_corpse_cape"), "main");
    public static final ModelLayerLocation ARMOR_LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("persistentbodies", "player_corpse_armor"), "main");
    public static final ModelLayerLocation SLIM_ARMOR_LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("persistentbodies", "player_corpse_slim_armor"), "main");

    private final ModelPart root;
    private final ModelPart head, body, rightArm, leftArm, rightLeg, leftLeg;
    private final ModelPart cape;
    private final ModelPart hat, jacket, rightSleeve, leftSleeve, rightPants, leftPants;
    // Outer armor layer (helmet, chestplate, boots) and inner layer (leggings), like vanilla's two armor models.
    private final ModelPart armorHead, armorBody, armorRightArm, armorLeftArm, armorRightBoot, armorLeftBoot;
    private final ModelPart armorLegsBody, armorRightLeg, armorLeftLeg;

    public PlayerCorpseModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
        this.cape = root.getChild("cape");
        this.hat = root.getChild("hat");
        this.jacket = root.getChild("jacket");
        this.rightSleeve = root.getChild("right_sleeve");
        this.leftSleeve = root.getChild("left_sleeve");
        this.rightPants = root.getChild("right_pants");
        this.leftPants = root.getChild("left_pants");
        this.armorHead = root.getChild("armor_head");
        this.armorBody = root.getChild("armor_body");
        this.armorRightArm = root.getChild("armor_right_arm");
        this.armorLeftArm = root.getChild("armor_left_arm");
        this.armorRightBoot = root.getChild("armor_right_boot");
        this.armorLeftBoot = root.getChild("armor_left_boot");
        this.armorLegsBody = root.getChild("armor_legs_body");
        this.armorRightLeg = root.getChild("armor_right_leg");
        this.armorLeftLeg = root.getChild("armor_left_leg");
    }

    public static LayerDefinition createBodyLayer() { return createBodyLayer(false, 64, false); }
    public static LayerDefinition createSlimBodyLayer() { return createBodyLayer(true, 64, false); }
    public static LayerDefinition createCapeBodyLayer() { return createBodyLayer(false, 32, false); }
    public static LayerDefinition createArmorBodyLayer() { return createBodyLayer(false, 32, true); }
    public static LayerDefinition createSlimArmorBodyLayer() { return createBodyLayer(true, 32, true); }

    private static LayerDefinition createBodyLayer(boolean slim, int textureHeight) { return createBodyLayer(slim, textureHeight, false); }

    private static LayerDefinition createBodyLayer(boolean slim, int textureHeight, boolean armorAtlas) {
        float armWidth = slim ? 3.0F : 4.0F;
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // Geometry and UV layout are the vanilla PlayerModel ones. Arms: the right arm box starts at -3 (or -2 slim)
        // and the left one at -1, with pivots at +-5, so each arm sits flush against the torso.
        float rightArmX = slim ? -2.0F : -3.0F;
        CubeDeformation skinLayer = new CubeDeformation(0.25F);
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -4, -4, 8, 8, 8), PartPose.offset(0, -10, 0));
        root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16).addBox(-4, -6, -2, 8, 12, 4), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(rightArmX, -2, -2, armWidth, 12, 4), PartPose.offset(-5, -4, 0));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create().texOffs(32, 48).addBox(-1, -2, -2, armWidth, 12, 4), PartPose.offset(5, -4, 0));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4), PartPose.offset(-1.9F, 6, 0));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2, 0, -2, 4, 12, 4), PartPose.offset(1.9F, 6, 0));

        // Second skin layer. CubeDeformation inflates the box WITHOUT changing its UV size; building the inflated
        // boxes by hand (e.g. 8.5 wide) stretched the overlay UVs and made the second layer look displaced.
        root.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 0).addBox(-4, -4, -4, 8, 8, 8, new CubeDeformation(0.5F)), PartPose.offset(0, -10, 0));
        root.addOrReplaceChild("jacket", CubeListBuilder.create().texOffs(16, 32).addBox(-4, -6, -2, 8, 12, 4, skinLayer), PartPose.ZERO);
        root.addOrReplaceChild("right_sleeve", CubeListBuilder.create().texOffs(40, 32).addBox(rightArmX, -2, -2, armWidth, 12, 4, skinLayer), PartPose.offset(-5, -4, 0));
        root.addOrReplaceChild("left_sleeve", CubeListBuilder.create().texOffs(48, 48).addBox(-1, -2, -2, armWidth, 12, 4, skinLayer), PartPose.offset(5, -4, 0));
        root.addOrReplaceChild("right_pants", CubeListBuilder.create().texOffs(0, 32).addBox(-2, 0, -2, 4, 12, 4, skinLayer), PartPose.offset(-1.9F, 6, 0));
        root.addOrReplaceChild("left_pants", CubeListBuilder.create().texOffs(0, 48).addBox(-2, 0, -2, 4, 12, 4, skinLayer), PartPose.offset(1.9F, 6, 0));

        // Armor uses the vanilla 64x32 armor atlas. Like vanilla, the outer layer (layer_1: helmet, chestplate,
        // boots) is inflated by 1.0 and the inner layer (layer_2: leggings) by 0.5, so the pieces never z-fight.
        // Inflating with CubeDeformation keeps the UV mapping identical to the vanilla armor models.
        CubeDeformation outer = new CubeDeformation(1.0F);
        CubeDeformation inner = new CubeDeformation(0.5F);
        root.addOrReplaceChild("armor_head", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -4, -4, 8, 8, 8, outer), PartPose.offset(0, -10, 0));
        root.addOrReplaceChild("armor_body", CubeListBuilder.create().texOffs(16, 16).addBox(-4, -6, -2, 8, 12, 4, outer), PartPose.ZERO);
        root.addOrReplaceChild("armor_right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-3, -2, -2, 4, 12, 4, outer), PartPose.offset(-5, -4, 0));
        root.addOrReplaceChild("armor_left_arm", CubeListBuilder.create().texOffs(40, 16).mirror().addBox(-1, -2, -2, 4, 12, 4, outer), PartPose.offset(5, -4, 0));
        root.addOrReplaceChild("armor_right_boot", CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4, outer), PartPose.offset(-1.9F, 6, 0));
        root.addOrReplaceChild("armor_left_boot", CubeListBuilder.create().texOffs(0, 16).mirror().addBox(-2, 0, -2, 4, 12, 4, outer), PartPose.offset(1.9F, 6, 0));
        root.addOrReplaceChild("armor_legs_body", CubeListBuilder.create().texOffs(16, 16).addBox(-4, -6, -2, 8, 12, 4, inner), PartPose.ZERO);
        root.addOrReplaceChild("armor_right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4, inner), PartPose.offset(-1.9F, 6, 0));
        root.addOrReplaceChild("armor_left_leg", CubeListBuilder.create().texOffs(0, 16).mirror().addBox(-2, 0, -2, 4, 12, 4, inner), PartPose.offset(1.9F, 6, 0));

        // Cape texture uses the standard 64x32 player cape atlas.
        root.addOrReplaceChild("cape", CubeListBuilder.create().texOffs(0, 0).addBox(-5, 0, 0, 10, 16, 1), PartPose.offset(0, -6, 2.1F));
        return LayerDefinition.create(mesh, 64, textureHeight);
    }

    @Override public void setupAnim(PlayerCorpseEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        applySleep(ageInTicks);
    }

    /**
     * Sleeping pose: the head slowly turns from side to side with a slight tilt, and the arms rise and fall a little
     * with the breathing. The skin layers and the armor pieces follow the part they cover.
     */
    public void applySleep(float age) {
        float breath = (float) Math.sin(age * 0.08F);
        float turn = (float) Math.sin(age * 0.021F) * 0.32F + (float) Math.sin(age * 0.047F) * 0.08F;
        float tilt = (float) Math.sin(age * 0.033F + 1.0F) * 0.07F;
        float armSwing = breath * 0.035F;

        head.yRot = turn;
        head.zRot = tilt;
        hat.yRot = turn;
        hat.zRot = tilt;
        armorHead.yRot = turn;
        armorHead.zRot = tilt;

        rightArm.xRot = armSwing;
        rightSleeve.xRot = armSwing;
        armorRightArm.xRot = armSwing;
        leftArm.xRot = -armSwing;
        leftSleeve.xRot = -armSwing;
        armorLeftArm.xRot = -armSwing;
    }

    @Override public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        renderPart(head, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(body, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(rightArm, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(leftArm, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(rightLeg, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(leftLeg, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        // Outer skin layers are independent so their UV regions stay aligned with the official skin atlas.
        renderPart(hat, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(jacket, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(rightSleeve, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(leftSleeve, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(rightPants, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        renderPart(leftPants, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
    }

    private void renderPart(ModelPart part, PoseStack poseStack, VertexConsumer buffer, int light, int overlay, float red, float green, float blue, float alpha) {
        part.render(poseStack, buffer, light, overlay, red, green, blue, alpha);
    }

    public void renderCape(PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
        cape.render(poseStack, buffer, light, overlay, 1, 1, 1, 1);
    }

    public void renderArmor(EquipmentSlot slot, PoseStack poseStack, VertexConsumer buffer, int light, int overlay, float r, float g, float b) {
        switch (slot) {
            case HEAD -> armorHead.render(poseStack, buffer, light, overlay, r, g, b, 1);
            case CHEST -> {
                armorBody.render(poseStack, buffer, light, overlay, r, g, b, 1);
                armorRightArm.render(poseStack, buffer, light, overlay, r, g, b, 1);
                armorLeftArm.render(poseStack, buffer, light, overlay, r, g, b, 1);
            }
            case LEGS -> {
                armorLegsBody.render(poseStack, buffer, light, overlay, r, g, b, 1);
                armorRightLeg.render(poseStack, buffer, light, overlay, r, g, b, 1);
                armorLeftLeg.render(poseStack, buffer, light, overlay, r, g, b, 1);
            }
            case FEET -> {
                armorRightBoot.render(poseStack, buffer, light, overlay, r, g, b, 1);
                armorLeftBoot.render(poseStack, buffer, light, overlay, r, g, b, 1);
            }
            default -> { }
        }
    }
}
