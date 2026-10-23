package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.configui.client.ConfigUi;
import com.example.examplemod.zones.match.MatchConfigPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.UUID;

/** One correlated server request at a time; local drafts never activate a match. */
public final class MatchConfigClient {
    private static boolean registered;
    private static long mapGeneration;
    public static void mapsChanged() { mapGeneration++; }
    static long mapGeneration() { return mapGeneration; }
    private static MatchConfigScreen active;
    private static UUID pending;
    private static Connection pendingConnection;
    private static int pendingTicks, pollTicks = 40;

    private MatchConfigClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MatchConfigPayloads.setClientHandler(MatchConfigClient::accept);
        NeoForge.EVENT_BUS.addListener(MatchConfigClient::onTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    public static void open(Screen parent) {
        open(parent, 0);
    }

    /** Only saved map IDs are accepted; the server still resolves the authoritative map and template. */
    public static void open(Screen parent, long mapId) {
        if (mapId < 0 || mapId == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_config_client.enter_valid_map_id"));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        Screen root = parent == null ? null : ConfigUi.root(parent);
        Screen cached = root == null ? null : ConfigUi.remembered(root, 3);
        if (cached instanceof MatchConfigScreen match) {
            if (active != match) { clear(); active = match; }
            minecraft.setScreen(active);
            if (mapId > 0) active.chooseSavedMap(mapId); else active.onMapEditorReturned();
            return;
        }
        if (active != null && active.parent() == parent && (parent == null || ConfigUi.root(active) == root)) {
            minecraft.setScreen(active);
            if (mapId > 0) active.chooseSavedMap(mapId); else active.onMapEditorReturned();
            return;
        }
        clear();
        active = new MatchConfigScreen(parent);
        minecraft.setScreen(active);
        request(active, mapId);
    }

    static void request(MatchConfigScreen screen, long mapId) {
        if (screen != active || pending != null) return;
        begin(screen, false, false, "read");
        try { MatchConfigPayloads.request(pending, mapId); }
        catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.failed_read_match", error.getMessage())); }
    }

    static void save(MatchConfigScreen screen) {
        if (screen != active || pending != null) return;
        try {
            var settings = screen.validatedDraft();
            begin(screen, true, false, "save");
            MatchConfigPayloads.save(pending, screen.revision(), settings);
        } catch (RuntimeException error) { fail(screen, Texts.tr("message.tacz_pubg.configuration.save_failed", error.getMessage())); }
    }

    static void savePreset(MatchConfigScreen screen, String previousName) {
        if (screen != active || pending != null) return;
        try { var settings = screen.validatedDraft(); begin(screen, true, false, "presetSave"); MatchConfigPayloads.savePreset(pending, screen.revision(), settings, previousName); }
        catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.failed_save_preset", error.getMessage())); }
    }
    static void loadPreset(MatchConfigScreen screen, String name) {
        if (screen != active || pending != null) return;
        begin(screen, false, false, "presetLoad");
        try { MatchConfigPayloads.loadPreset(pending, screen.mapId(), name); }
        catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.failed_load_preset", error.getMessage())); }
    }
    static void deletePreset(MatchConfigScreen screen, String name) {
        if (screen != active || pending != null) return;
        begin(screen, true, false, "presetDelete");
        try { MatchConfigPayloads.deletePreset(pending, screen.revision(), screen.mapId(), name); }
        catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.failed_delete_rule", error.getMessage())); }
    }

    static void saveLobby(MatchConfigScreen screen) {
        if (screen != active || pending != null) return;
        try {
            var room = screen.validatedLobby();
            begin(screen, true, false, "lobbySave");
            MatchConfigPayloads.saveLobby(pending, screen.lobbyRevision(), room);
        } catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.failed_save_roster", error.getMessage())); }
    }

    static void readLobby(MatchConfigScreen screen, boolean preserveDraft) {
        if (screen != active || pending != null || screen.mapId() <= 0) return;
        begin(screen, false, false, preserveDraft ? "lobbyRebase" : "lobbyRead");
        try { MatchConfigPayloads.request(pending, screen.mapId()); }
        catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.failed_read_roster", error.getMessage())); }
    }

    static void control(MatchConfigScreen screen, String action) {
        if (screen != active || pending != null || screen.mapId() <= 0) return;
        if ("start".equals(action) && screen.hasUnsavedChanges()) {
            screen.requestFailed(Texts.tr("screen.tacz_pubg.match_config_client.save_current_rules_roster_before_starting_server_match"));
            return;
        }
        begin(screen, !"refresh".equals(action), "refresh".equals(action), "control");
        try { MatchConfigPayloads.control(pending, "start".equals(action) ? screen.revision() : screen.controlRevision(), screen.mapId(), action, screen.lobbyRevision()); }
        catch (RuntimeException error) { fail(screen, Texts.tr("screen.tacz_pubg.match_config_client.match_operation_failed", error.getMessage())); }
    }

    private static void begin(MatchConfigScreen screen, boolean writing, boolean statusOnly, String kind) {
        pending = UUID.randomUUID();
        var connection = Minecraft.getInstance().getConnection();
        pendingConnection = connection == null ? null : connection.getConnection();
        pendingTicks = 400;
        screen.setPending(writing, statusOnly, kind);
    }

    private static void fail(MatchConfigScreen screen, String message) {
        pending = null;
        pendingConnection = null;
        pendingTicks = 0;
        screen.requestFailed(message);
    }

    private static void accept(MatchConfigPayloads.Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) { minecraft.execute(() -> accept(snapshot)); return; }
        var connection = minecraft.getConnection();
        if (active == null || pending == null || !pending.equals(snapshot.request())
                || connection == null || connection.getConnection() != pendingConnection
                || minecraft.player == null || minecraft.level == null) return;
        pending = null;
        pendingConnection = null;
        pendingTicks = 0;
        pollTicks = 40;
        active.accept(snapshot);
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (pendingTicks > 0 && --pendingTicks == 0 && active != null)
            fail(active, Texts.tr("screen.tacz_pubg.match_config_client.server_confirmation_timed_out_draft_retained_reload_check"));
        if (active != null && pending == null && Minecraft.getInstance().screen == active && active.shouldPollStatus()) {
            if (--pollTicks <= 0) { pollTicks = 40; control(active, "refresh"); }
        } else pollTicks = 40;
    }

    static void closed(MatchConfigScreen screen) { if (active == screen) clear(); }

    private static void clear() {
        active = null;
        pending = null;
        pendingConnection = null;
        pendingTicks = 0;
        pollTicks = 40;
    }
}
