package com.example.examplemod.zones.client;

import com.example.examplemod.localization.Texts;

import java.util.UUID;

import com.example.examplemod.zones.MapZoneConfigPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Correlates independent map edits; the parent loot screen keeps its own draft. */
public final class MapZoneConfigClient {
    private static boolean registered;
    private static MapZoneConfigScreen active;
    private static UUID pending;
    private static int pendingTicks;

    private MapZoneConfigClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MapZoneConfigPayloads.setClientHandler(MapZoneConfigClient::accept);
        NeoForge.EVENT_BUS.addListener(MapZoneConfigClient::onTick);
        NeoForge.EVENT_BUS.addListener(MapZoneConfigClient::onLogout);
    }

    public static void open(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        if (active != null && active.parent() == parent) {
            active.showMapList();
            minecraft.setScreen(active);
            return;
        }
        active = new MapZoneConfigScreen(parent);
        minecraft.setScreen(active);
        request(active, -1, 0, 0);
    }

    static void request(MapZoneConfigScreen screen, long revision, int offset, long detailMapId) {
        if (screen != active || pending != null) return;
        begin(screen, false);
        try {
            MapZoneConfigPayloads.request(pending, revision, offset, screen.pageSize(), detailMapId);
        } catch (RuntimeException error) {
            fail(screen, Texts.tr("screen.tacz_pubg.map_zone_config_client.failed_read_maps", error.getMessage()));
        }
    }

    static void save(MapZoneConfigScreen screen) {
        if (screen != active || pending != null) return;
        try {
            var draft = screen.validatedDraft();
            begin(screen, true);
            MapZoneConfigPayloads.save(pending, screen.revision(), draft, screen.offset(), screen.pageSize());
        } catch (RuntimeException error) {
            fail(screen, Texts.tr("message.tacz_pubg.configuration.save_failed", error.getMessage()));
        }
    }

    static void delete(MapZoneConfigScreen screen, long mapId) {
        if (screen != active || pending != null) return;
        begin(screen, true);
        try {
            MapZoneConfigPayloads.delete(pending, screen.revision(), mapId, screen.offset(), screen.pageSize());
        } catch (RuntimeException error) {
            fail(screen, Texts.tr("message.tacz_pubg.configuration.delete_failed", error.getMessage()));
        }
    }

    static void chooseEntityRecording(MapZoneConfigScreen screen, long mapId, boolean record) {
        if (screen != active || pending != null || !screen.entityRecordingPending(mapId)) return;
        begin(screen, true);
        try {
            MapZoneConfigPayloads.chooseEntityRecording(pending, screen.revision(), mapId, record, screen.offset(), screen.pageSize());
        } catch (RuntimeException error) {
            fail(screen, Texts.tr("message.tacz_pubg.configuration.save_failed", error.getMessage()));
        }
    }

    private static void begin(MapZoneConfigScreen screen, boolean writing) {
        pending = UUID.randomUUID();
        pendingTicks = 400;
        screen.setPending(writing);
    }

    private static void fail(MapZoneConfigScreen screen, String message) {
        pending = null;
        pendingTicks = 0;
        screen.requestFailed(message);
    }

    private static void accept(MapZoneConfigPayloads.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> accept(snapshot));
            return;
        }
        if (active == null || pending == null || !pending.equals(snapshot.request())
                || minecraft.level == null || minecraft.player == null) return;
        pending = null;
        pendingTicks = 0;
        if (snapshot.outcome() == MapZoneConfigPayloads.SAVED || snapshot.outcome() == MapZoneConfigPayloads.DELETED)
            com.example.examplemod.zones.match.client.MatchConfigClient.mapsChanged();
        active.accept(snapshot);
    }

    static void closed(MapZoneConfigScreen screen) {
        if (active != screen) return;
        active = null;
        pending = null;
        pendingTicks = 0;
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (pendingTicks > 0 && --pendingTicks == 0 && active != null) {
            fail(active, Texts.tr("screen.tacz_pubg.map_zone_config_client.server_confirmation_timed_out_draft_retained_reload_check"));
        }
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        active = null;
        pending = null;
        pendingTicks = 0;
    }
}
