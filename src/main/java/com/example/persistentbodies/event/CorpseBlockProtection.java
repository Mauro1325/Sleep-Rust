package com.example.persistentbodies.event;

import com.example.persistentbodies.entity.PlayerCorpseEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Prevents placing a block directly over a body so it cannot be buried/hidden by building. */
public class CorpseBlockProtection {
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) return;
        BlockPos pos = event.getPos();
        AABB search = new AABB(pos).inflate(0.2D, 1.5D, 0.2D);
        for (PlayerCorpseEntity corpse : level.getEntitiesOfClass(PlayerCorpseEntity.class, search)) {
            AABB body = corpse.getBoundingBox();
            boolean overlapsHorizontally = body.maxX > pos.getX() && body.minX < pos.getX() + 1.0D
                    && body.maxZ > pos.getZ() && body.minZ < pos.getZ() + 1.0D;
            int bodyY = BlockPos.containing(corpse.getX(), corpse.getY(), corpse.getZ()).getY();
            if (overlapsHorizontally && pos.getY() >= bodyY && pos.getY() <= bodyY + 1) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
