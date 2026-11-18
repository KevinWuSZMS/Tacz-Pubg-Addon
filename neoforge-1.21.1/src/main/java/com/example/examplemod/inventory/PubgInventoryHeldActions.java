package com.example.examplemod.inventory;

import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Resets only a held gun actually changed by a committed inventory transaction. */
public final class PubgInventoryHeldActions {
    private PubgInventoryHeldActions() { }

    /** Call after publishing committed inventory changes, including when the old gun became empty. */
    public static void gunChanged(ServerPlayer player) {
        // Native draw clears reload/bolt/melee/charge and Lua state immediately. cancelReload alone
        // only asks the old gun's script to interrupt and can leave a finishing reload active.
        IGunOperator.fromLivingEntity(player).draw(player::getMainHandItem);
        // An empty sound item distinguishes this one reset from attachment install/uninstall sounds.
        PacketDistributor.sendToPlayer(player, new PubgInventoryPayloads.Feedback(
                "", ItemStack.EMPTY, false, player.getInventory().selected));
    }
}
