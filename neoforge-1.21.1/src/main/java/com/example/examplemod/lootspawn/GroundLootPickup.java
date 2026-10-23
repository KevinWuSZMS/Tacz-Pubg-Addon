package com.example.examplemod.lootspawn;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/** Authority stays on the server: a packet names a candidate, never grants a pickup. */
public final class GroundLootPickup {
    private GroundLootPickup() {}

    public static double reach(Player player) {
        return Math.min(4.5, player.entityInteractionRange());
    }

    public static boolean canPickup(Player player, GroundLootEntity loot) {
        if (player instanceof net.minecraft.server.level.ServerPlayer p && com.example.examplemod.combat.CombatService.actionBlocked(p)) return false;
        if (player.level().isClientSide || player.isSpectator() || !player.isAlive()
                || (!player.getMainHandItem().isEmpty() && !com.example.examplemod.inventory.InventoryConfig.active(player)) || player.level() != loot.level()
                || loot.isRemoved() || !loot.isManagedLoot() || loot.getItem().isEmpty() || loot.getQuantity() <= 0
                || !loot.canBePickedUpBy(player)) return false;
        Vec3 eye = player.getEyePosition(), end = eye.add(player.getViewVector(1).scale(reach(player)));
        var block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        var candidates = new ArrayList<GroundLootRaycast.Target>();
        for (GroundLootEntity candidate : player.level().getEntitiesOfClass(GroundLootEntity.class, new AABB(eye, end).inflate(3))) {
            if (!candidate.isManagedLoot() || candidate.isRemoved() || candidate.getItem().isEmpty() || candidate.getQuantity() <= 0) continue;
            candidates.add(new GroundLootRaycast.Target(candidate.getId(), GroundLootFootprints.selectionBounds(candidate.getItem(), candidate.level()).inflate(.025),
                    candidate.position(), candidate.getYRot()));
        }
        // Recompute the nearest managed target instead of accepting any intersected ID.
        // Vanilla drops are intentionally absent from the candidate set.
        return GroundLootRaycast.nearest(eye, end, eye.distanceToSqr(block.getLocation()), candidates)
                .map(hit -> hit.id() == loot.getId()).orElse(false);
    }
}
