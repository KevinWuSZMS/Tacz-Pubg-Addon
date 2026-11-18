package com.example.examplemod.inventory;

import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Commit a source-stack acquisition only after the displaced equipment has a safe destination. */
public final class EquipmentPickupTransfers {
    private EquipmentPickupTransfers() {}
    public static boolean automatic(Player player, ItemStack incoming) {
        if (!InventoryConfig.active(player) || player.level().isClientSide
                || !InventoryConfig.current(player).autoEquipmentReplacement()
                || !EquipmentInventoryRules.mayReplace(player, incoming, true)) return false;
        var category = EquipmentInventoryRules.category(incoming);
        ItemStack previous = category.equipped(player).copy();
        GroundLootEntity dropped = null;
        if (!previous.isEmpty()) {
            dropped = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
            if (dropped == null) return false;
            dropped.initializeDrop(previous, BlockPos.containing(player.position()).below(), 10, null);
            dropped.setPos(player.getX(), player.getY() + .15, player.getZ());
            dropped.setYRot(player.getYRot());
            if (!player.level().addFreshEntity(dropped)) { dropped.discard(); return false; }
        }
        try {
            category.equip(player, incoming.copyWithCount(1));
        } catch (RuntimeException failure) {
            if (dropped != null) dropped.discard();
            category.equip(player, previous);
            return false;
        }
        incoming.shrink(1);
        player.getInventory().setChanged();
        return true;
    }
}
