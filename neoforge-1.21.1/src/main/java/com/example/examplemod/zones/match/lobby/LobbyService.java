package com.example.examplemod.zones.match.lobby;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.gunbalance.GunBalanceService;
import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneSavedData;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchConfigSavedData;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.MatchSession;
import com.example.examplemod.zones.match.MatchSpawnPreparation;
import com.example.examplemod.zones.match.bot.MatchBotService;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Explicit room membership, preflight and all-or-nothing bot preparation on the server thread. */
public final class LobbyService {
    private static final Map<MinecraftServer, Map<Long, Preparing>> PREPARING = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<Long, String>> FAILURES = new WeakHashMap<>();
    private static final class Preparing {
        final MapDefinition map; final MatchConfigModel.Settings settings; final LobbyModel.Room room;
        UUID token, spawnToken; boolean canceled, terrainReady;
        MatchSpawnPreparation.Plan spawnPlan;
        Preparing(MapDefinition map, MatchConfigModel.Settings settings, LobbyModel.Room room) {
            this.map = map; this.settings = settings; this.room = room;
        }
    }
    private LobbyService() {}

    public static void forgetDeletedMap(MinecraftServer server, long mapId) {
        var failures = FAILURES.get(server);
        if (failures != null) failures.remove(mapId);
    }

    public static boolean isPreparing(MinecraftServer server, long mapId) {
        var pending = PREPARING.get(server); return pending != null && pending.containsKey(mapId);
    }
    public static boolean preparingParticipant(MinecraftServer server, UUID id) {
        var pending = PREPARING.get(server);
        return pending != null && pending.values().stream().anyMatch(p -> p.room.members().stream()
                .anyMatch(m -> !LobbyModel.isBot(m) && m.id().equals(id)));
    }
    public static LobbyModel.View view(MinecraftServer server, long mapId) {
        LobbySavedData saved = readable(server);
        var settings = MatchConfigSavedData.get(server).settings(mapId);
        var room = saved.room(mapId, settings.teamSize());
        MapDefinition map = requireMap(server, mapId);
        var online = server.getPlayerList().getPlayers().stream().filter(p -> !MatchBotService.isCarpetPlayer(p))
                .sorted(Comparator.comparing(p -> p.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER))
                .limit(LobbyModel.MAX_ONLINE).map(p -> {
                    String problem = eligibility(server, map, p, true);
                    return new LobbyModel.OnlinePlayer(p.getUUID(), p.getGameProfile().getName(), problem.isEmpty(),
                            problem.isEmpty() ? map.stagingSpawn() == null
                                    ? Texts.tr("message.tacz_pubg.lobby_service.ready_inside_map")
                                    : Texts.tr("message.tacz_pubg.map_staging.ready") : problem);
                }).toList();
        Preparing pending = PREPARING.getOrDefault(server, Map.of()).get(mapId);
        int requested = (int) room.members().stream().filter(LobbyModel::isBot).count();
        String failure = FAILURES.getOrDefault(server, Map.of()).getOrDefault(mapId, "");
        boolean running = MatchService.session(server, mapId) != null;
        int ready = pending == null ? running ? requested : 0
                : pending.token == null ? 0 : MatchBotService.preparedCount(server, pending.token);
        String stage = pending != null ? "spawning" : running ? "running" : failure.isEmpty() ? "idle" : "failed";
        String message = pending != null ? pending.terrainReady ? pending.spawnToken != null && pending.spawnPlan == null
                ? Texts.tr("message.tacz_pubg.map_staging.loading")
                : Texts.tr("message.tacz_pubg.lobby_service.map_restored_registering_bot_slots_match_preparation_begins")
                : Texts.tr("message.tacz_pubg.lobby_service.restoring_map_terrain_containers_bot_registration_game_start")
                : running ? Texts.tr("message.tacz_pubg.lobby_service.match_uses_frozen_roster_rules") : failure.isEmpty() ? Texts.tr("message.tacz_pubg.lobby_service.save_roster_rules_then_start_match") : failure;
        return new LobbyModel.View(saved.revision(), room, online, stage, Math.min(ready, requested), requested,
                MatchBotService.available(), truncate(message));
    }
    public static void save(MinecraftServer server, long expectedRevision, LobbyModel.Room room) {
        requireThread(server); LobbyModel.requireValid(room); requireMap(server, room.mapId());
        LobbySavedData saved = readable(server);
        if (MatchService.isRunning(server, room.mapId())) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.adjust_roster_during_lobby_preparation"));
        var previous = saved.room(room.mapId(), room.teamSize());
        List<LobbyModel.Member> canonical = new ArrayList<>();
        for (var member : room.members().stream().sorted(Comparator.comparingInt(m -> LobbyModel.seatIndex(room, m))).toList()) {
            if (LobbyModel.isBot(member)) { canonical.add(new LobbyModel.Member(member.id(), member.name(), "bot", member.team(), member.slot(), member.difficulty(), "scavenger")); continue; }
            ServerPlayer player = server.getPlayerList().getPlayer(member.id());
            if (player != null && MatchBotService.isCarpetPlayer(player)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.add_bots_through_bot_slots"));
            if (player == null && previous.members().stream().noneMatch(old -> !LobbyModel.isBot(old) && old.id().equals(member.id())))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.select_new_members_online_player_list"));
            if (MatchService.participantRunning(server, member.id()) || preparingParticipant(server, member.id()))
                throw new IllegalArgumentException(member.name() + Texts.tr("message.tacz_pubg.lobby_service.another_match_end_match_first"));
            canonical.add(new LobbyModel.Member(member.id(), player == null ? member.name() : player.getGameProfile().getName(),
                    "human", member.team(), member.slot(), "normal", "rifle"));
        }
        saved.saveRoom(expectedRevision, new LobbyModel.Room(room.mapId(), room.teamSize(), canonical));
        var failures = FAILURES.get(server); if (failures != null) failures.remove(room.mapId());
    }
    public static String start(MinecraftServer server, long mapId, long expectedRevision) {
        requireThread(server); LobbySavedData saved = readable(server);
        if (saved.revision() != expectedRevision) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.lobby_version_changed_read_review_roster"));
        if (MatchService.isRunning(server, mapId)) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.map_already_has_preparing_active_match"));
        var templates = MatchConfigSavedData.get(server);
        if (!templates.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.restore_match_templates_first", templates.loadError()));
        MapDefinition map = requireMap(server, mapId); var settings = templates.settings(mapId);
        if (server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension())) == null)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.load_map_dimension_first"));
        LobbyModel.Room room = saved.room(mapId, settings.teamSize()); LobbyModel.requireValid(room);
        if (room.members().size() < settings.minPlayers() || room.members().size() > settings.maxPlayers())
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.participant_count", settings.minPlayers(), settings.maxPlayers()));
        if (room.teamSize() != settings.teamSize()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.set_same_team_size_roster_match_rules"));
        if (settings.autoEnd() && room.members().stream().map(LobbyModel.Member::team).distinct().count() < 2)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.automatic_settlement_requires_least_two_teams"));
        map = com.example.examplemod.zones.match.MatchMapPreparation.prepare(map, settings, server.overworld().getRandom().nextLong());
        validateHumans(server, map, room);
        var specs = room.members().stream().filter(LobbyModel::isBot).map(m -> new MatchBotService.BotSpec(m.id(), m.name(),
                team(mapId, m.team()), "scavenger", m.difficulty())).toList();
        Map<Long, String> failures = FAILURES.computeIfAbsent(server, ignored -> new HashMap<>()); failures.remove(mapId);
        if (!specs.isEmpty()) GunBalanceService.requireSettingsReady(server);
        Preparing pending = new Preparing(map, settings, room);
        PREPARING.computeIfAbsent(server, ignored -> new HashMap<>()).put(mapId, pending);
        try {
            MapResetService.beforeStart(server, map, () -> prepareAfterReset(server, pending, specs),
                    error -> preparationFailed(server, pending, error));
        } catch (RuntimeException error) {
            PREPARING.get(server).remove(mapId); failures.put(mapId, truncate(message(error))); throw error;
        }
        return Texts.tr("message.tacz_pubg.lobby_service.pre_match_restoration_scheduled_slots_will_registered_game");
    }
    private static void prepareAfterReset(MinecraftServer server, Preparing pending, List<MatchBotService.BotSpec> specs) {
        if (pending.canceled || PREPARING.getOrDefault(server, Map.of()).get(pending.map.id()) != pending) return;
        pending.terrainReady = true;
        validateHumans(server, pending.map, pending.room);
        if (pending.map.stagingSpawn() == null) prepareBodies(server, pending, specs);
        else pending.spawnToken = MatchSpawnPreparation.begin(server, pending.map,
                pending.room.members().stream().sorted(Comparator.comparingInt(m -> LobbyModel.seatIndex(pending.room, m)))
                        .map(LobbyModel.Member::id).toList(),
                plan -> {
                    if (pending.canceled || PREPARING.getOrDefault(server, Map.of()).get(pending.map.id()) != pending) {
                        plan.rollback(); return;
                    }
                    pending.spawnPlan = plan;
                    validateHumans(server, pending.map, pending.room);
                    prepareBodies(server, pending, specs);
                }, error -> preparationFailed(server, pending, error));
    }
    private static void prepareBodies(MinecraftServer server, Preparing pending, List<MatchBotService.BotSpec> specs) {
        if (specs.isEmpty()) complete(server, pending, null);
        else pending.token = MatchBotService.prepare(server, pending.map, specs,
                pending.spawnPlan == null ? Map.of() : pending.spawnPlan.positions(), result -> complete(server, pending, result));
    }
    private static void preparationFailed(MinecraftServer server, Preparing pending, String error) {
        pending.canceled = true;
        if (pending.token != null) MatchBotService.cancel(server, pending.token);
        if (pending.spawnToken != null) MatchSpawnPreparation.cancel(server, pending.spawnToken);
        if (pending.spawnPlan != null) pending.spawnPlan.rollback();
        var preparations = PREPARING.get(server); if (preparations != null) preparations.remove(pending.map.id(), pending);
        MapResetService.ended(server, pending.map);
        String text = truncate(Texts.tr("message.tacz_pubg.lobby_service.match_start_incomplete", error));
        FAILURES.computeIfAbsent(server, ignored -> new HashMap<>()).put(pending.map.id(), text);
        broadcast(server, pending.room, text);
    }
    private static void complete(MinecraftServer server, Preparing pending, MatchBotService.Preparation result) {
        long mapId = pending.map.id();
        if (pending.canceled || PREPARING.getOrDefault(server, Map.of()).get(mapId) != pending) {
            if (result != null) MatchBotService.cancel(server, result.requestToken());
            if (pending.spawnPlan != null) pending.spawnPlan.rollback();
            return;
        }
        if (result != null) pending.token = result.requestToken();
        MatchSession started = null;
        try {
            if (result != null && !result.error().isEmpty()) throw new IllegalStateException(result.error());
            validateHumans(server, pending.map, pending.room);
            List<MatchEngine.Participant> roster = roster(server, pending.map, pending.room, result == null ? List.of() : result.bots());
            if (pending.spawnPlan != null) pending.spawnPlan.teleportHumans(pending.room.members().stream()
                    .filter(member -> !LobbyModel.isBot(member)).map(LobbyModel.Member::id).toList());
            started = MatchService.startRoster(server, pending.map, pending.settings, roster);
            if (result != null) MatchBotService.bind(server, result.requestToken(), started, MatchService.roster(server, mapId));
            if (pending.spawnPlan != null) pending.spawnPlan.commit();
            broadcast(server, pending.room, Texts.tr("message.tacz_pubg.lobby_service.map_all_slots_ready_entering_match_preparation", mapId));
        } catch (RuntimeException error) {
            if (started != null) MatchService.end(server, mapId);
            else MapResetService.ended(server, pending.map);
            if (pending.token != null) MatchBotService.cancel(server, pending.token);
            if (pending.spawnPlan != null) pending.spawnPlan.rollback();
            String text = truncate(Texts.tr("message.tacz_pubg.lobby_service.match_preparation_failed", message(error)));
            FAILURES.computeIfAbsent(server, ignored -> new HashMap<>()).put(mapId, text); broadcast(server, pending.room, text);
        } finally { var pendingMaps = PREPARING.get(server); if (pendingMaps != null) pendingMaps.remove(mapId); }
    }
    private static List<MatchEngine.Participant> roster(MinecraftServer server, MapDefinition map,
            LobbyModel.Room room, List<MatchBotService.PreparedBot> bots) {
        Map<UUID, MatchBotService.PreparedBot> actual = new HashMap<>(); HashSet<UUID> identities = new HashSet<>();
        for (var bot : bots) if (actual.putIfAbsent(bot.plannedId(), bot) != null || !identities.add(bot.actualId()))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.register_distinct_bot_identities_again"));
        var roster = new ArrayList<MatchEngine.Participant>();
        for (var member : room.members().stream().sorted(Comparator.comparingInt(m -> LobbyModel.seatIndex(room, m))).toList()) {
            UUID id = member.id(); String name = member.name();
            if (LobbyModel.isBot(member)) {
                var bot = actual.get(id);
                if (bot == null || !team(map.id(), member.team()).equals(bot.teamId()) || !MatchBotService.owned(server, bot.actualId()))
                    throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.wait_every_bot_slot_finish_registration"));
                id = bot.actualId(); name = bot.name();
            } else {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) throw new IllegalStateException(name + Texts.tr("message.tacz_pubg.lobby_service.online_ready"));
                name = player.getGameProfile().getName();
            }
            roster.add(new MatchEngine.Participant(id, name, team(map.id(), member.team())));
        }
        if (bots.size() != room.members().stream().filter(LobbyModel::isBot).count()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.check_number_registered_bot_slots"));
        return List.copyOf(roster);
    }
    public static String end(MinecraftServer server, long mapId) {
        requireThread(server); Map<Long, Preparing> pending = PREPARING.get(server);
        Preparing preparation = pending == null ? null : pending.remove(mapId);
        if (preparation != null) {
            preparation.canceled = true;
            if (preparation.token != null) MatchBotService.cancel(server, preparation.token);
            if (preparation.spawnToken != null) MatchSpawnPreparation.cancel(server, preparation.spawnToken);
            if (preparation.spawnPlan != null) preparation.spawnPlan.rollback();
            MapResetService.ended(server, preparation.map);
            return Texts.tr("message.tacz_pubg.lobby_service.match_preparation_cancelled_map_restoration_will_complete_lobby");
        }
        if (MapResetService.starting(server, mapId) && MatchService.session(server, mapId) == null) {
            MapResetService.ended(server, requireMap(server, mapId)); return Texts.tr("message.tacz_pubg.lobby_service.match_start_cancelled_map_restoration_will_complete");
        }
        MatchService.end(server, mapId); return Texts.tr("message.tacz_pubg.lobby_service.administrator_ended_match_map_restoring_automatically_lobby_roster");
    }
    private static void validateHumans(MinecraftServer server, MapDefinition map, LobbyModel.Room room) {
        for (var member : room.members()) if (!LobbyModel.isBot(member)) {
            if (PREPARING.getOrDefault(server, Map.of()).entrySet().stream().anyMatch(entry -> entry.getKey() != map.id()
                    && entry.getValue().room.members().stream().anyMatch(m -> !LobbyModel.isBot(m) && m.id().equals(member.id()))))
                throw new IllegalArgumentException(member.name() + Texts.tr("message.tacz_pubg.lobby_service.already_registered_another_lobby"));
            ServerPlayer player = server.getPlayerList().getPlayer(member.id());
            String problem = player == null ? Texts.tr("message.tacz_pubg.lobby_service.online_ready.validate_humans") : MatchBotService.isCarpetPlayer(player) ? Texts.tr("message.tacz_pubg.lobby_service.use_bot_slot") : eligibility(server, map, player, false);
            if (!problem.isEmpty()) throw new IllegalArgumentException(member.name() + "：" + problem);
        }
    }
    private static String eligibility(MinecraftServer server, MapDefinition map, ServerPlayer player, boolean includePending) {
        if (MatchService.participantRunning(server, player.getUUID()) || includePending && preparingParticipant(server, player.getUUID()))
            return Texts.tr("message.tacz_pubg.lobby_service.already_registered_active_match");
        if (!player.isAlive() || player.isRemoved()) return Texts.tr("message.tacz_pubg.lobby_service.alive_online_ready");
        if (map.stagingSpawn() == null && (!map.dimension().equals(player.serverLevel().dimension().location()) || !map.contains(player.getX(), player.getZ())))
            return Texts.tr("message.tacz_pubg.lobby_service.enter_map_s_bounds_become_ready");
        return "";
    }
    private static MapDefinition requireMap(MinecraftServer server, long mapId) {
        var data = MapZoneSavedData.get(server);
        if (!data.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.restore_map_data_first", data.loadError()));
        var map = data.map(mapId);
        if (map == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.select_saved_map", mapId));
        return map;
    }
    private static LobbySavedData readable(MinecraftServer server) {
        var data = LobbySavedData.get(server);
        if (!data.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.original_lobby_data_protected", data.loadError())); return data;
    }
    private static String team(long map, int team) { return "lobby:" + map + ":team:" + team; }
    private static void broadcast(MinecraftServer server, LobbyModel.Room room, String text) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.hasPermissions(2)
                || room.members().stream().anyMatch(m -> m.id().equals(p.getUUID()))) p.sendSystemMessage(Texts.component(text));
    }
    public static void stopped(MinecraftServer server) {
        var pending = PREPARING.remove(server);
        if (pending != null) for (Preparing preparation : pending.values()) {
            if (preparation.spawnToken != null) MatchSpawnPreparation.cancel(server, preparation.spawnToken);
            if (preparation.spawnPlan != null) preparation.spawnPlan.rollback();
        }
        FAILURES.remove(server);
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.lobby_service.manage_lobbies_server_thread"));
    }
    private static String message(RuntimeException e) { return e.getMessage() == null ? Texts.tr("message.tacz_pubg.lobby_service.check_lobby_rules_slots") : e.getMessage(); }
    private static String truncate(String text) { return Texts.limit(text, Texts.MAX_MESSAGE_LENGTH); }
}
