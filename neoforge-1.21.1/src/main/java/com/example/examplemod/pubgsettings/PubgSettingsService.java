package com.example.examplemod.pubgsettings;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class PubgSettingsService {
    private static PubgSettings client = PubgSettings.initial();
    private PubgSettingsService() {}
    public static PubgSettings server(MinecraftServer server) { return PubgSettingsSavedData.get(server).settings(); }
    public static PubgSettings client() { return client; }
    public static void acceptClient(PubgSettings next) { client = next; }
    public static void clearClient() { client = PubgSettings.initial(); }
    public static void register(IEventBus bus) {
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) PubgSettingsPayloads.send(p,0);
        });
    }
}
