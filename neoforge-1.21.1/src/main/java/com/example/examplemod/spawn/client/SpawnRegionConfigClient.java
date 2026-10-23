package com.example.examplemod.spawn.client;

import com.example.examplemod.localization.Texts;

import java.util.UUID;

import com.example.examplemod.spawn.SpawnRegionConfigPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client lifetime/correlation for the independently saved region screen. */
public final class SpawnRegionConfigClient {
    private static boolean registered;
    private static SpawnRegionConfigScreen active;
    private static UUID pending;
    private static int pendingTicks;

    private SpawnRegionConfigClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        SpawnRegionConfigPayloads.setClientHandler(SpawnRegionConfigClient::accept);
        NeoForge.EVENT_BUS.addListener(SpawnRegionConfigClient::onTick);
        NeoForge.EVENT_BUS.addListener(SpawnRegionConfigClient::onLogout);
    }

    public static void open(Screen parent, double density) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        if (!Double.isFinite(density) || density < 0) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_region_config_client.invalid_loot_density"));
        if (active != null && active.parent() == parent) {
            minecraft.setScreen(active);
            return;
        }
        active = new SpawnRegionConfigScreen(parent, density);
        minecraft.setScreen(active);
        request(active, -1, 0);
    }

    static void request(SpawnRegionConfigScreen screen, long revision, int offset) {
        if (screen != active || pending != null) return;
        begin(screen, false);
        try {
            SpawnRegionConfigPayloads.request(pending, revision, offset, screen.pageSize());
        } catch (RuntimeException error) {
            fail(screen, Texts.tr("screen.tacz_pubg.spawn_region_config_client.region_request_failed", error.getMessage()));
        }
    }

    static void save(SpawnRegionConfigScreen screen) {
        if (screen != active || pending != null) return;
        try {
            var changes = screen.changes();
            begin(screen, true);
            SpawnRegionConfigPayloads.save(pending, screen.revision(), changes, screen.offset(), screen.pageSize());
        } catch (RuntimeException error) {
            fail(screen, Texts.tr("screen.tacz_pubg.loot_config_client.submission_failed", error.getMessage()));
        }
    }

    private static void begin(SpawnRegionConfigScreen screen, boolean saving) {
        pending = UUID.randomUUID();
        pendingTicks = 400;
        screen.setPending(saving);
    }

    private static void fail(SpawnRegionConfigScreen screen, String message) {
        pending = null;
        pendingTicks = 0;
        screen.requestFailed(message);
    }

    private static void accept(SpawnRegionConfigPayloads.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> accept(snapshot));
            return;
        }
        if (active == null || pending == null || !pending.equals(snapshot.request()) || minecraft.level == null) return;
        pending = null;
        pendingTicks = 0;
        active.accept(snapshot);
    }

    static void closed(SpawnRegionConfigScreen screen) {
        if (active != screen) return;
        active = null;
        pending = null;
        pendingTicks = 0;
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (pendingTicks > 0 && --pendingTicks == 0 && active != null) {
            fail(active, Texts.tr("screen.tacz_pubg.loot_config_client.server_confirmation_timed_out_draft_preserved_check_current"));
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        active = null;
        pending = null;
        pendingTicks = 0;
    }
}
