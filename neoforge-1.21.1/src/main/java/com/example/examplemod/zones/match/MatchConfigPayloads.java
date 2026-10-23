package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneCommands;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneSavedData;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import com.example.examplemod.zones.match.lobby.LobbyModel;
import com.example.examplemod.zones.match.lobby.LobbySavedData;
import com.example.examplemod.zones.match.lobby.LobbyService;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Independent OP-only match-template edits and explicit runtime controls. */
public final class MatchConfigPayloads {
    public static final int READ = 0, SAVED = 1, CONFLICT = 2, FAILED = 3, CONTROLLED = 4, PRESET_LOADED = 5;
    private static final Set<String> ACTIONS = Set.of("start", "end", "refresh", "pause", "resume");
    private static final Set<String> STAGES = Set.of("preparing", "no_circle", "waiting", "shrinking", "final", "paused", "finished", "stopped");
    private static volatile Consumer<Snapshot> clientHandler = ignored -> {};
    private static volatile Consumer<MatchStatus> hudHandler = ignored -> {};
    private static final Map<MinecraftServer, Long> HUD_SEQUENCES = new WeakHashMap<>();

    private MatchConfigPayloads() {}

    public record MapHeader(long id, String name, String dimension, boolean hasSafeZone) {
        public MapHeader {
            if (id < 1 || id == Long.MAX_VALUE || name == null || name.length() > 80 || dimension == null
                    || dimension.length() > 128 || ResourceLocation.tryParse(dimension) == null)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_map_summary_fields"));
        }
    }

    public record RulePreset(String name) {
        public RulePreset { MatchRulePresets.checkName(name); }
    }

    public record Snapshot(UUID request, long revision, List<MapHeader> maps, long selectedMapId,
            Settings settings, MatchStatus status, int outcome, String message, LobbyModel.View lobby, List<RulePreset> presets,
            String preferredRuleName) implements CustomPacketPayload {
        public Snapshot(UUID request, long revision, List<MapHeader> maps, long selectedMapId, Settings settings,
                MatchStatus status, int outcome, String message, LobbyModel.View lobby, List<RulePreset> presets) {
            this(request, revision, maps, selectedMapId, settings, status, outcome, message, lobby, presets, "");
        }
        public Snapshot(UUID request, long revision, List<MapHeader> maps, long selectedMapId, Settings settings, MatchStatus status, int outcome, String message, LobbyModel.View lobby) {
            this(request, revision, maps, selectedMapId, settings, status, outcome, message, lobby, List.of());
        }
        public Snapshot(UUID request, long revision, List<MapHeader> maps, long selectedMapId,
                Settings settings, MatchStatus status, int outcome, String message) {
            this(request, revision, maps, selectedMapId, settings, status, outcome, message, null);
        }
        public static final Type<Snapshot> TYPE = MatchConfigPayloads.type("match_config_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = codec(b -> {
            UUID request = b.readUUID(); long revision = b.readVarLong(); long selected = b.readVarLong();
            int outcome = b.readVarInt(); String message = b.readUtf(Texts.MAX_MESSAGE_LENGTH);
            int count = b.readVarInt();
            if (count < 0 || count > MapZoneModel.MAX_MAPS) throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.too_many_match_maps"));
            List<MapHeader> maps = new ArrayList<>(count);
            for (int i = 0; i < count; i++) maps.add(new MapHeader(b.readVarLong(), b.readUtf(80), b.readUtf(128), b.readBoolean()));
            Settings settings = b.readBoolean() ? MatchConfigModel.parse(readJson(b)) : null;
            var status = readStatus(b); var lobby = readLobby(b);
            int presetsCount = b.readVarInt();
            if (presetsCount < 0 || presetsCount > MatchRulePresets.MAX_PRESETS) throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.too_many_rule_presets"));
            List<RulePreset> presets = new ArrayList<>();
            for (int i = 0; i < presetsCount; i++) presets.add(new RulePreset(b.readUtf(80)));
            return new Snapshot(request, revision, maps, selected, settings, status, outcome, message, lobby, presets, b.readUtf(80));
        }, (b, p) -> {
            b.writeUUID(p.request()); b.writeVarLong(p.revision()); b.writeVarLong(p.selectedMapId());
            b.writeVarInt(p.outcome()); b.writeUtf(p.message(), Texts.MAX_MESSAGE_LENGTH); b.writeVarInt(p.maps().size());
            for (MapHeader map : p.maps()) {
                b.writeVarLong(map.id()); b.writeUtf(map.name(), 80); b.writeUtf(map.dimension(), 128); b.writeBoolean(map.hasSafeZone());
            }
            b.writeBoolean(p.settings() != null);
            if (p.settings() != null) writeJson(b, MatchConfigModel.toJson(p.settings()));
            writeStatus(b, p.status());
            writeLobby(b, p.lobby());
            b.writeVarInt(p.presets().size());
            for (RulePreset preset : p.presets()) b.writeUtf(preset.name(), 80);
            b.writeUtf(p.preferredRuleName(), 80);
        });
        public Snapshot {
            Objects.requireNonNull(request); Objects.requireNonNull(message); maps = List.copyOf(maps); presets = List.copyOf(presets);
            Objects.requireNonNull(preferredRuleName);
            if (!preferredRuleName.isEmpty()) MatchRulePresets.checkName(preferredRuleName);
            if (presets.size() > MatchRulePresets.MAX_PRESETS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.rule_library_exceeds_limit"));
            if (revision < -1 || selectedMapId < 0 || maps.size() > MapZoneModel.MAX_MAPS || outcome < READ
                    || outcome > PRESET_LOADED || message.length() > Texts.MAX_MESSAGE_LENGTH) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_match_configuration_response_fields"));
            if (settings != null && settings.mapId() != selectedMapId) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_match_template_against_selected_map_id"));
            if (status != null && status.mapId() != selectedMapId) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_match_status_against_selected_map_id"));
            if (lobby != null && lobby.room().mapId() != selectedMapId) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_lobby_against_selected_map_id"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Request(UUID request, long mapId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = MatchConfigPayloads.type("match_config_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = codec(
                b -> new Request(b.readUUID(), b.readVarLong()), (b, p) -> { b.writeUUID(p.request()); b.writeVarLong(p.mapId()); });
        public Request { Objects.requireNonNull(request); if (mapId < 0 || mapId == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.match_config_client.enter_valid_map_id")); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Save(UUID request, long revision, String json) implements CustomPacketPayload {
        public static final Type<Save> TYPE = MatchConfigPayloads.type("match_config_save");
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = codec(
                b -> new Save(b.readUUID(), b.readVarLong(), readJson(b)),
                (b, p) -> { b.writeUUID(p.request()); b.writeVarLong(p.revision()); writeJson(b, p.json()); });
        public Save { Objects.requireNonNull(request); Objects.requireNonNull(json); if (revision < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_configuration_version")); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SaveLobby(UUID request, long revision, String json) implements CustomPacketPayload {
        public static final Type<SaveLobby> TYPE = MatchConfigPayloads.type("match_lobby_save");
        public static final StreamCodec<RegistryFriendlyByteBuf, SaveLobby> CODEC = codec(
                b -> new SaveLobby(b.readUUID(), b.readVarLong(), readJson(b, LobbyModel.MAX_JSON_BYTES)),
                (b, p) -> { b.writeUUID(p.request()); b.writeVarLong(p.revision()); writeJson(b, p.json(), LobbyModel.MAX_JSON_BYTES); });
        public SaveLobby {
            Objects.requireNonNull(request); Objects.requireNonNull(json);
            if (revision < 0 || json.getBytes(StandardCharsets.UTF_8).length > LobbyModel.MAX_JSON_BYTES)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_lobby_version_configuration_length"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Control(UUID request, long revision, long mapId, String action, long lobbyRevision) implements CustomPacketPayload {
        public Control(UUID request, long revision, long mapId, String action) { this(request, revision, mapId, action, 0); }
        public static final Type<Control> TYPE = MatchConfigPayloads.type("match_config_control");
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = codec(
                b -> new Control(b.readUUID(), b.readVarLong(), b.readVarLong(), b.readUtf(16), b.readVarLong()),
                (b, p) -> { b.writeUUID(p.request()); b.writeVarLong(p.revision()); b.writeVarLong(p.mapId()); b.writeUtf(p.action(), 16); b.writeVarLong(p.lobbyRevision()); });
        public Control {
            Objects.requireNonNull(request);
            if (revision < 0 || mapId < 1 || mapId == Long.MAX_VALUE || action == null || !ACTIONS.contains(action)
                    || lobbyRevision < -1 || "start".equals(action) && lobbyRevision < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_match_action_map_id"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Preset(UUID request, long revision, long mapId, String name, String json, String action, String previousName) implements CustomPacketPayload {
        public static final Type<Preset> TYPE = MatchConfigPayloads.type("match_rule_preset");
        public static final StreamCodec<RegistryFriendlyByteBuf, Preset> CODEC = codec(
                b -> new Preset(b.readUUID(), b.readVarLong(), b.readVarLong(), b.readUtf(80), readJson(b), b.readUtf(8), b.readUtf(80)),
                (b, p) -> { b.writeUUID(p.request()); b.writeVarLong(p.revision()); b.writeVarLong(p.mapId()); b.writeUtf(p.name(), 80); writeJson(b, p.json()); b.writeUtf(p.action(), 8); b.writeUtf(p.previousName(), 80); });
        public Preset { Objects.requireNonNull(request); MatchRulePresets.checkName(name); Objects.requireNonNull(json);
            Objects.requireNonNull(previousName);
            if (!previousName.isEmpty()) MatchRulePresets.checkName(previousName);
            if (!List.of("save", "load", "delete").contains(action)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.match_config_payloads.invalid_rule_action"));
            if (revision < 0 || mapId < 1 || mapId == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_rule_library_request")); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Hud(long sequence, MatchStatus status) implements CustomPacketPayload {
        public static final Type<Hud> TYPE = MatchConfigPayloads.type("match_hud");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hud> CODEC = codec(
                b -> new Hud(b.readVarLong(), readStatus(b)), (b, p) -> { b.writeVarLong(p.sequence()); writeStatus(b, p.status()); });
        public Hud { if (sequence < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.check_match_hud_version")); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void setClientHandler(Consumer<Snapshot> handler) { clientHandler = Objects.requireNonNull(handler); }
    public static void setHudHandler(Consumer<MatchStatus> handler) { hudHandler = Objects.requireNonNull(handler); }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("8-circle-clock");
        registrar.playToServer(Preset.TYPE, Preset.CODEC, (p, c) -> server(c, p.request(), player -> preset(player, p)));
        registrar.playToServer(Request.TYPE, Request.CODEC, (p, c) -> server(c, p.request(), player -> send(player, p.request(), p.mapId(), READ, Texts.tr("message.tacz_pubg.match_config_payloads.after_saving_rules_click_start_match_apply_them"))));
        registrar.playToServer(Save.TYPE, Save.CODEC, (p, c) -> server(c, p.request(), player -> save(player, p)));
        registrar.playToServer(SaveLobby.TYPE, SaveLobby.CODEC, (p, c) -> server(c, p.request(), player -> saveLobby(player, p)));
        registrar.playToServer(Control.TYPE, Control.CODEC, (p, c) -> server(c, p.request(), player -> control(player, p)));
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (p, c) -> c.enqueueWork(() -> clientHandler.accept(p)));
        registrar.playToClient(Hud.TYPE, Hud.CODEC, (p, c) -> c.enqueueWork(() -> hudHandler.accept(p.status())));
    }
    public static void registerGame(IEventBus bus) {
        bus.addListener(MatchConfigPayloads::tick);
        bus.addListener((ServerStoppedEvent e) -> HUD_SEQUENCES.remove(e.getServer()));
    }
    public static void request(UUID request, long mapId) { PacketDistributor.sendToServer(new Request(request, mapId)); }
    public static void save(UUID request, long expectedRevision, Settings settings) {
        PacketDistributor.sendToServer(new Save(request, expectedRevision, MatchConfigModel.toJson(settings)));
    }
    public static void control(UUID request, long expectedRevision, long mapId, String action) {
        PacketDistributor.sendToServer(new Control(request, expectedRevision, mapId, action));
    }
    public static void control(UUID request, long expectedRevision, long mapId, String action, long lobbyRevision) {
        PacketDistributor.sendToServer(new Control(request, expectedRevision, mapId, action, lobbyRevision));
    }
    public static void saveLobby(UUID request, long expectedRevision, LobbyModel.Room room) {
        PacketDistributor.sendToServer(new SaveLobby(request, expectedRevision, LobbyModel.toJson(room)));
    }

    public static void savePreset(UUID request, long expectedRevision, Settings settings, String previousName) {
        PacketDistributor.sendToServer(new Preset(request, expectedRevision, settings.mapId(), settings.name(), MatchConfigModel.toJson(settings), "save", previousName));
    }
    public static void loadPreset(UUID request, long mapId, String name) {
        PacketDistributor.sendToServer(new Preset(request, 0, mapId, name, "{}", "load", ""));
    }
    public static void deletePreset(UUID request, long expectedRevision, long mapId, String name) {
        PacketDistributor.sendToServer(new Preset(request, expectedRevision, mapId, name, "{}", "delete", ""));
    }
    private static void preset(ServerPlayer player, Preset packet) {
        var data = MatchConfigSavedData.get(player.server);
        if (!data.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_config_payloads.original_match_data_protected_restore_reading_before_editing"));
        if (MapZoneSavedData.get(player.server).map(packet.mapId()) == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.select_saved_map"));
        var presets = MatchRulePresets.get(player.server);
        if (!packet.action().equals("load")) {
            if (data.revision() != packet.revision() || data.revision() == Long.MAX_VALUE) {
                send(player, packet.request(), packet.mapId(), CONFLICT, Texts.tr("message.tacz_pubg.match_config_payloads.rule_library_version_changed_draft_retained_reload")); return;
            }
            if (packet.action().equals("delete")) {
                presets.delete(packet.name()); data.forgetRememberedRule(packet.name()); data.recordPresetChange(packet.revision());
                send(player, packet.request(), packet.mapId(), CONTROLLED, Texts.tr("message.tacz_pubg.match_config_payloads.rule_deleted"));
            } else {
                Settings settings = MatchConfigModel.parse(packet.json());
                if (settings.mapId() != packet.mapId() || !settings.name().equals(packet.name()))
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.rules_map_id_do_not_match"));
                presets.saveRule(packet.previousName(), packet.name(), settings);
                data.renameRememberedRule(packet.previousName(), packet.name()); data.recordPresetChange(packet.revision());
                send(player, packet.request(), packet.mapId(), CONTROLLED, Texts.tr("message.tacz_pubg.match_config_payloads.named_rule_configuration_saved"));
            }
        } else {
            Settings loaded = presets.load(packet.name(), packet.mapId());
            send(player, packet.request(), packet.mapId(), PRESET_LOADED, Texts.tr("message.tacz_pubg.match_config_payloads.rules_loaded_into_draft_save_configuration_use_them"), loaded);
        }
    }

    private static void server(IPayloadContext context, UUID request, Consumer<ServerPlayer> action) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(player, new Snapshot(request, -1, List.of(), 0, null, null, FAILED, Texts.tr("message.tacz_pubg.match_config_payloads.op_permission_level_required_edit_control_matches")));
                return;
            }
            try { action.accept(player); }
            catch (RuntimeException error) { send(player, request, 0, FAILED, message(error)); }
        });
    }
    private static void save(ServerPlayer player, Save packet) {
        MatchConfigSavedData data = MatchConfigSavedData.get(player.server);
        Settings settings = MatchConfigModel.parse(packet.json());
        if (data.revision() != packet.revision()) {
            send(player, packet.request(), settings.mapId(), CONFLICT, Texts.tr("message.tacz_pubg.match_config_payloads.match_template_version_changed_draft_retained_reload_review")); return;
        }
        try {
            data.saveSettings(packet.revision(), settings);
            send(player, packet.request(), settings.mapId(), SAVED, Texts.tr("message.tacz_pubg.match_config_payloads.next_match_rules_saved_click_start_match_run"));
        } catch (RuntimeException error) { send(player, packet.request(), settings.mapId(), FAILED, message(error)); }
    }
    private static void control(ServerPlayer player, Control packet) {
        MatchConfigSavedData data = MatchConfigSavedData.get(player.server);
        if (packet.action().equals("start") && data.revision() != packet.revision()) {
            send(player, packet.request(), packet.mapId(), CONFLICT, Texts.tr("message.tacz_pubg.match_config_payloads.template_version_changed_reload_review_before_proceeding")); return;
        }
        if (packet.action().equals("start") && LobbySavedData.get(player.server).revision() != packet.lobbyRevision()) {
            send(player, packet.request(), packet.mapId(), CONFLICT, Texts.tr("message.tacz_pubg.match_config_payloads.lobby_version_changed_read_review_roster")); return;
        }
        try {
            String text = switch (packet.action()) {
                case "start" -> LobbyService.start(player.server, packet.mapId(), packet.lobbyRevision());
                case "end" -> LobbyService.end(player.server, packet.mapId());
                case "pause" -> { MatchService.pause(player.server, packet.mapId()); yield Texts.tr("message.tacz_pubg.match_config_payloads.match_paused_timers_shrinking_bot_actions_paused"); }
                case "resume" -> { MatchService.resume(player.server, packet.mapId()); yield Texts.tr("message.tacz_pubg.match_config_payloads.match_resumed"); }
                case "refresh" -> Texts.tr("message.tacz_pubg.match_config_payloads.current_match_status_read");
                default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_config_payloads.select_valid_match_action"));
            };
            send(player, packet.request(), packet.mapId(), CONTROLLED, text);
        } catch (RuntimeException error) { send(player, packet.request(), packet.mapId(), FAILED, message(error)); }
    }
    private static void saveLobby(ServerPlayer player, SaveLobby packet) {
        LobbyModel.Room room = LobbyModel.parse(packet.json());
        if (LobbySavedData.get(player.server).revision() != packet.revision()) {
            send(player, packet.request(), room.mapId(), CONFLICT, Texts.tr("message.tacz_pubg.match_config_payloads.lobby_version_changed_roster_draft_retained_read_review")); return;
        }
        try {
            LobbyService.save(player.server, packet.revision(), room);
            send(player, packet.request(), room.mapId(), SAVED, Texts.tr("message.tacz_pubg.match_config_payloads.lobby_roster_saved_these_slots_teams_will_used"));
        } catch (RuntimeException error) { send(player, packet.request(), room.mapId(), FAILED, message(error)); }
    }
    private static void send(ServerPlayer player, UUID request, long requestedMap, int outcome, String message) {
        send(player, request, requestedMap, outcome, message, null);
    }
    private static void send(ServerPlayer player, UUID request, long requestedMap, int outcome, String message, Settings loaded) {
        MatchConfigSavedData data = MatchConfigSavedData.get(player.server);
        MapZoneSavedData maps = MapZoneSavedData.get(player.server);
        LobbySavedData rooms = LobbySavedData.get(player.server);
        if (!data.loadError().isEmpty() || !maps.loadError().isEmpty() || !rooms.loadError().isEmpty()) {
            String error = !data.loadError().isEmpty() ? data.loadError() : !maps.loadError().isEmpty() ? maps.loadError() : rooms.loadError();
            PacketDistributor.sendToPlayer(player, new Snapshot(request, data.revision(), List.of(), 0, null, null, FAILED, truncate(Texts.tr("message.tacz_pubg.match_config_payloads.original_data_protected", error))));
            return;
        }
        var all = maps.maps();
        long selected = requestedMap;
        if (selected == 0) {
            var choice = MapZoneCommands.selectedMap(player);
            selected = choice == null ? all.isEmpty() ? 0 : all.getFirst().id() : choice.id();
        }
        if (selected > 0 && maps.map(selected) == null) { selected = 0; outcome = FAILED; message = Texts.tr("message.tacz_pubg.match_config_payloads.map_list_updated_select_map_again"); }
        List<MapHeader> headers = all.stream().map(m -> new MapHeader(m.id(), m.name(), m.dimension().toString(), m.safeZone() != null)).toList();
        var presets = MatchRulePresets.get(player.server).list();
        PacketDistributor.sendToPlayer(player, new Snapshot(request, data.revision(), headers, selected,
                selected == 0 ? null : loaded != null ? loaded : data.settings(selected), selected == 0 ? null : MatchService.status(player.server, selected), outcome, truncate(message),
                selected == 0 ? null : LobbyService.view(player.server, selected), presets,
                selected == 0 ? "" : data.preferredRuleName(selected, presets.stream().map(RulePreset::name).toList())));
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) return;
        long sequence = HUD_SEQUENCES.merge(server, 1L, Long::sum);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var map = com.example.examplemod.zones.MapOverlayService.currentMapFor(player);
            MatchStatus selected = map == null ? null : MatchService.status(server, map.id());
            PacketDistributor.sendToPlayer(player, new Hud(sequence, selected));
        }
    }
    public static boolean active(MatchStatus status) { return status != null && !status.stage().equals("finished") && !status.stage().equals("stopped"); }
    private static String message(RuntimeException error) { return error.getMessage() == null ? Texts.tr("message.tacz_pubg.match_commands.match_operation_failed") : error.getMessage(); }
    private static String truncate(String message) { return Texts.limit(message, Texts.MAX_MESSAGE_LENGTH); }

    public static MatchStatus readStatus(RegistryFriendlyByteBuf b) {
        if (!b.readBoolean()) return null;
        long map = b.readVarLong(); UUID session = b.readUUID(); String stage = b.readUtf(16);
        int phase = b.readVarInt(), count = b.readVarInt(), seconds = b.readVarInt();
        int players = b.readVarInt(), teams = b.readVarInt(), total = b.readVarInt();
        String result = b.readUtf(Texts.MAX_MESSAGE_LENGTH); Circle current = readCircle(b), target = readCircle(b);
        int stageTotal = b.readVarInt(); double flight = b.readDouble(); String bombing = b.readUtf(16); int bombSeconds = b.readVarInt();
        Circle phaseStart = readCircle(b);
        long elapsedTicks = b.readVarLong(), durationTicks = b.readVarLong(), matchTicks = b.readVarLong();
        String underlying = b.readUtf(16);
        if (elapsedTicks < 0 || durationTicks < 0 || elapsedTicks > durationTicks
                || durationTicks > MatchConfigModel.MAX_SECONDS * 20L || matchTicks < 0
                || !STAGES.contains(underlying) || underlying.equals("paused")
                || !stage.equals("paused") && !stage.equals(underlying))
            throw new DecoderException("Invalid authoritative circle clock");
        if (stageTotal < 0 || stageTotal > MatchConfigModel.MAX_SECONDS || !Double.isFinite(flight) || flight < -1 || flight > 1
                || bombing.length() > 16 || bombSeconds < 0 || bombSeconds > MatchConfigModel.MAX_SECONDS)
            throw new DecoderException("Invalid match flow status");
        if (map < 1 || !STAGES.contains(stage) || count < 1 || count > MatchConfigModel.MAX_PHASES || phase < 0 || phase > count
                || seconds < 0 || seconds > MatchConfigModel.MAX_SECONDS || players < 0 || players > total || teams < 0 || teams > players
                || total < 1 || total > MatchConfigModel.MAX_PLAYERS) throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.invalid_match_status"));
        return new MatchStatus(map, session, stage, phase, count, seconds, players, teams, total, result, current, target,
                stageTotal, flight, bombing, bombSeconds, phaseStart, elapsedTicks, durationTicks, matchTicks, underlying);
    }
    public static void writeStatus(RegistryFriendlyByteBuf b, MatchStatus s) {
        b.writeBoolean(s != null); if (s == null) return;
        b.writeVarLong(s.mapId()); b.writeUUID(s.sessionId()); b.writeUtf(s.stage(), 16);
        b.writeVarInt(s.phase()); b.writeVarInt(s.phaseCount()); b.writeVarInt(s.secondsRemaining());
        b.writeVarInt(s.playersAlive()); b.writeVarInt(s.teamsAlive()); b.writeVarInt(s.playersTotal());
        b.writeUtf(s.result(), Texts.MAX_MESSAGE_LENGTH); writeCircle(b, s.currentCircle()); writeCircle(b, s.targetCircle());
        b.writeVarInt(s.stageTotalSeconds()); b.writeDouble(s.flightProgress()); b.writeUtf(s.bombingStage(), 16); b.writeVarInt(s.bombingSeconds());
        writeCircle(b, s.phaseStartCircle()); b.writeVarLong(s.stageElapsedTicks()); b.writeVarLong(s.stageDurationTicks());
        b.writeVarLong(s.matchTicks()); b.writeUtf(s.underlyingStage(), 16);
    }
    private static Circle readCircle(RegistryFriendlyByteBuf b) {
        if (!b.readBoolean()) return null;
        double x = b.readDouble(), z = b.readDouble(), r = b.readDouble();
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(r) || Math.abs(x) > MapZoneModel.MAX_COORDINATE
                || Math.abs(z) > MapZoneModel.MAX_COORDINATE || r <= 0 || r > MapZoneModel.MAX_RADIUS) throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.invalid_match_circle"));
        return new Circle(x, z, r);
    }
    private static void writeCircle(RegistryFriendlyByteBuf b, Circle circle) {
        b.writeBoolean(circle != null); if (circle == null) return;
        b.writeDouble(circle.centerX()); b.writeDouble(circle.centerZ()); b.writeDouble(circle.radius());
    }
    private static String readJson(RegistryFriendlyByteBuf b) {
        return readJson(b, MatchConfigModel.MAX_JSON_BYTES);
    }
    private static String readJson(RegistryFriendlyByteBuf b, int limit) {
        byte[] bytes = b.readByteArray(limit);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.invalid_utf_8_match_template"), error); }
    }
    private static void writeJson(RegistryFriendlyByteBuf b, String json) {
        writeJson(b, json, MatchConfigModel.MAX_JSON_BYTES);
    }
    private static void writeJson(RegistryFriendlyByteBuf b, String json, int limit) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.match_config_payloads.match_template_too_large"));
        b.writeByteArray(bytes);
    }
    private static LobbyModel.View readLobby(RegistryFriendlyByteBuf b) {
        if (!b.readBoolean()) return null;
        long revision = b.readVarLong(); LobbyModel.Room room = LobbyModel.parse(readJson(b, LobbyModel.MAX_JSON_BYTES));
        int count = b.readVarInt();
        if (count < 0 || count > LobbyModel.MAX_ONLINE) throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.too_many_room_candidates"));
        List<LobbyModel.OnlinePlayer> online = new ArrayList<>(); Set<UUID> ids = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            UUID id = b.readUUID(); if (!ids.add(id)) throw new DecoderException(Texts.tr("error.tacz_pubg.match_config_payloads.duplicate_room_candidate"));
            online.add(new LobbyModel.OnlinePlayer(id, b.readUtf(32), b.readBoolean(), b.readUtf(Texts.MAX_MESSAGE_LENGTH)));
        }
        return new LobbyModel.View(revision, room, online, b.readUtf(16), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(Texts.MAX_MESSAGE_LENGTH));
    }
    private static void writeLobby(RegistryFriendlyByteBuf b, LobbyModel.View view) {
        b.writeBoolean(view != null); if (view == null) return;
        b.writeVarLong(view.revision()); writeJson(b, LobbyModel.toJson(view.room()), LobbyModel.MAX_JSON_BYTES);
        b.writeVarInt(view.online().size());
        for (var p : view.online()) { b.writeUUID(p.id()); b.writeUtf(p.name(), 32); b.writeBoolean(p.eligible()); b.writeUtf(p.message(), Texts.MAX_MESSAGE_LENGTH); }
        b.writeUtf(view.stage(), 16); b.writeVarInt(view.readyBots()); b.writeVarInt(view.requestedBots());
        b.writeBoolean(view.botsAvailable()); b.writeUtf(view.message(), Texts.MAX_MESSAGE_LENGTH);
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", name));
    }
    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(Function<RegistryFriendlyByteBuf, T> read, BiConsumer<RegistryFriendlyByteBuf, T> write) {
        return new StreamCodec<>() {
            @Override public T decode(RegistryFriendlyByteBuf buffer) { return read.apply(buffer); }
            @Override public void encode(RegistryFriendlyByteBuf buffer, T value) { write.accept(buffer, value); }
        };
    }
}
