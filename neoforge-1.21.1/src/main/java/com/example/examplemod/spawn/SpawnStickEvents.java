package com.example.examplemod.spawn;

import com.example.examplemod.ExampleMod;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class SpawnStickEvents {
    private SpawnStickEvents() {}

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity().getMainHandItem().getItem() instanceof ItemSpawnAreaStick)) return;
        // Cancel on BOTH sides, including hold/abort: creative breaking ignores useBlock/useItem.
        event.setCanceled(true);
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
                && event.getEntity() instanceof ServerPlayer player) {
            SpawnAreaService.selectFirst(player, event.getPos());
        }
    }
}
