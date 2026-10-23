package com.example.examplemod.gunbalance.client;

import com.example.examplemod.gunbalance.GunExperimentPayloads;
import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.client.reload.WorkbenchMagazineReloadClient;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Starts disabled and accepts only the server's successfully applied profile. */
public final class GunExperimentClient {
    private static boolean registered;
    private GunExperimentClient() {}

    public static boolean balanceEnabled() { return GunExperimentState.clientEnabled(); }
    public static boolean magazineEnabled() { return GunExperimentState.clientMagazineEnabled(); }

    public static void register() {
        if (registered) return;
        registered = true;
        GunExperimentPayloads.setClientHandler((state, connection) -> {
            var current = Minecraft.getInstance().getConnection();
            // A late queued confirmation from a disconnected server cannot affect a new session.
            if (current == null || current.getConnection() != connection) return;
            boolean previous = balanceEnabled();
            if (GunExperimentState.acceptClient(state.revision(), state.enabled(), state.magazineEnabled())) {
                if (previous != state.enabled()) PersistentRecoilClient.reset();
                // Cancel before a disabled snapshot could be reinterpreted at the ordinary clock rate.
                if (!state.magazineEnabled()) WorkbenchMagazineReloadClient.cancelActive();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    private static void clear() {
        GunExperimentState.clearClient();
        WorkbenchMagazineReloadClient.clear();
        PersistentRecoilClient.reset();
    }
}
