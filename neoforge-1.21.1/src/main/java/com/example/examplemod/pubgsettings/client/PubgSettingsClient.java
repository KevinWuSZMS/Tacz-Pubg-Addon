package com.example.examplemod.pubgsettings.client;

import com.example.examplemod.pubgsettings.PubgSettingsPayloads;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class PubgSettingsClient {
    private static net.minecraft.network.Connection currentConnection;
    private static long revision = -1;
    private PubgSettingsClient() {}
    public static long revision() { return revision; }
    private static void clear() { currentConnection=null; revision=-1; PubgSettingsService.clearClient(); }
    public static void register() {
        PubgSettingsPayloads.setClient((state,connection) -> {
            var mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.getConnection().getConnection() != connection) return;
            if (currentConnection != connection) { clear(); currentConnection=connection; }
            if (state.revision() >= revision) { revision=state.revision(); PubgSettingsService.acceptClient(state.settings()); }
            // An old acknowledged request can still close its own pending transaction,
            // but can never roll back the live settings received from a newer revision.
            if (mc.screen instanceof PubgSettingsScreen screen) screen.accept(state);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> clear());
    }
}
