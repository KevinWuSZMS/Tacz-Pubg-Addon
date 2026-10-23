package com.example.examplemod.modelscale;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = "tacz_pubg")
public final class ModelScaleEvents {
    private ModelScaleEvents() {}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) ModelScalePayloads.send(p, 0);
    }
    @SubscribeEvent public static void reload(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) ModelScalePayloads.send(event.getPlayer(), 0);
        else ModelScalePayloads.sync(event.getPlayerList().getServer());
    }
}
