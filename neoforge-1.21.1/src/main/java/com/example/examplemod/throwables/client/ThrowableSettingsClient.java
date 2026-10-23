package com.example.examplemod.throwables.client;

import com.example.examplemod.throwables.ThrowableSettingsPayloads;
import com.example.examplemod.throwables.ThrowableSettingsService;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class ThrowableSettingsClient {
    private static long revision = -1;
    private ThrowableSettingsClient() {}
    public static void register() {
        ThrowableSettingsPayloads.setHandler((state, connection) -> {
            var mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.getConnection().getConnection() != connection) return;
            if (state.revision() >= revision) {
                revision = state.revision();
                ThrowableSettingsService.acceptClient(JsonParser.parseString(state.json()).getAsJsonObject());
            }
            if (mc.screen instanceof ThrowableSettingsScreen screen) screen.accept(state);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            revision = -1; ThrowableSettingsService.clearClient();
        });
    }
}
