package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneSavedData;
import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.operatorpermissions.OperatorPermissions;
import com.example.examplemod.operatorpermissions.OperatorPermissions.Permission;
import com.example.examplemod.operatorpermissions.OperatorPermissionsService;
import com.example.examplemod.zones.bombing.BombingService;
import com.example.examplemod.zones.match.bot.MatchBotService;
import com.example.examplemod.zones.match.lobby.LobbyService;
import com.example.examplemod.zones.match.flight.MatchFlightService;
import com.example.examplemod.backpack.MatchInventorySnapshots;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-authoritative roster, staging, flight and circles. */
public final class MatchService {
    public static final ResourceKey<DamageType> BLUE_ZONE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath("tacz_pubg", "blue_zone"));
    private static final Map<MinecraftServer, MatchRuntimeState> STATES = new WeakHashMap<>();
    // Only session-bound mode markers persist on a player; matches never resume on restart.
    private static final String MODE_RESTORE = "tacz_pubg_match_mode_restore";
    // Start-time approval is tied to this session so changing the next-start policy cannot eliminate a live OP.
    private static final String MODE_KEEP = "tacz_pubg_match_mode_keep";
    private MatchService() {}

    public static void register(IEventBus bus) {
        BombingService.register(bus);
        bus.addListener(MatchService::onServerTick);
        bus.addListener(MatchService::onServerStopped);
        bus.addListener(EventPriority.LOWEST, MatchService::onDeath);
        bus.addListener(MatchService::onIncomingDamage);
        bus.addListener(MatchService::onLogin);
        // Flight login recovery runs at normal priority; restore originals only afterwards.
        bus.addListener(EventPriority.LOWEST, (PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) MatchInventorySnapshots.restoreIfStale(player);
        });
        bus.addListener(MatchService::onLogout);
        bus.addListener(MatchService::onClone);
        bus.addListener(MatchService::onRespawn);
    }
    public static long revision(MinecraftServer server) {
        MatchRuntimeState state = STATES.get(server); return state == null ? 0 : state.revision();
    }
    public static boolean isRunning(MinecraftServer server, long mapId) {
        return session(server, mapId) != null || LobbyService.isPreparing(server, mapId) || MapResetService.starting(server, mapId);
    }
    public static void forgetDeletedMap(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server);
        if (state != null) state.forgetDeletedMap(mapId);
    }
    public static boolean participantRunning(MinecraftServer server, UUID id) {
        MatchRuntimeState state = STATES.get(server); return state != null && state.participantRunning(id);
    }
    /** Personal membership, independent of the map being watched or edited by this player. */
    @Nullable public static MatchEngine participantGame(MinecraftServer server, UUID id) {
        MatchRuntimeState state = STATES.get(server);
        return state == null ? null : state.engines().stream().filter(game -> game.contains(id)).findFirst().orElse(null);
    }
    public static boolean alive(MinecraftServer server, long mapId, UUID id) {
        MatchRuntimeState state = STATES.get(server);
        MatchEngine game = state == null ? null : state.engine(mapId); return game != null && game.alive(id);
    }
    public static List<MatchEngine.Participant> roster(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server);
        MatchEngine game = state == null ? null : state.engine(mapId); return game == null ? List.of() : game.roster();
    }
    /** Null selects the ordinary scoreboard overlay; room matches use their frozen team and slot order. */
    @Nullable public static List<MatchEngine.Participant> teamRoster(MinecraftServer server, UUID viewer) {
        MatchRuntimeState state = STATES.get(server); if (state == null) return null;
        for (MatchEngine game : state.engines()) if (game.contains(viewer)) {
            if (!game.alive(viewer)) return List.of();
            String team = game.roster().stream().filter(p -> p.id().equals(viewer)).findFirst().orElseThrow().teamId();
            return game.roster().stream().filter(p -> p.teamId().equals(team)).toList();
        }
        return null;
    }
    public static boolean aliveParticipant(MinecraftServer server, UUID id) {
        MatchRuntimeState state = STATES.get(server);
        return state != null && state.engines().stream().anyMatch(game -> game.alive(id));
    }
    @Nullable public static MapDefinition activeMap(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server); return state == null ? null : state.activeMap(mapId);
    }
    public static List<MapDefinition> activeMaps(MinecraftServer server) {
        MatchRuntimeState state = STATES.get(server); return state == null ? List.of() : state.activeMaps();
    }
    public static List<MatchEngine> activeGames(MinecraftServer server) {
        MatchRuntimeState state = STATES.get(server);
        return state == null ? List.of() : List.copyOf(state.engines());
    }
    @Nullable public static MatchSession session(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server); return state == null ? null : state.session(mapId);
    }
    public static List<MatchSession> matches(MinecraftServer server) {
        MatchRuntimeState state = STATES.get(server); return state == null ? List.of() : state.sessions();
    }
    @Nullable public static MatchStatus status(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server); return state == null ? null : state.status(mapId);
    }
    public static long elapsedTicks(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server); return state == null ? 0 : state.elapsedTicks(mapId);
    }
    public static String start(MinecraftServer server, long mapId) {
        if (isRunning(server, mapId)) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.map_already_has_preparing_active_match"));
        var map = MapZoneSavedData.get(server).map(mapId);
        if (map == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_does_not_exist", mapId));
        MapResetService.beforeStart(server, map, () -> {
            if (startNow(server, mapId) != null)
                server.getPlayerList().broadcastSystemMessage(Texts.of("message.tacz_pubg.match_service.map_restored_match_started", mapId), false);
        }, error -> server.getPlayerList().getPlayers().stream().filter(p -> p.hasPermissions(2))
                .forEach(p -> p.sendSystemMessage(Texts.of("message.tacz_pubg.match_service.match_did_not_start_map", mapId, error))));
        return Texts.tr("message.tacz_pubg.match_service.pre_match_restoration_scheduled_once_complete_participants_will");
    }
    private static MatchSession startNow(MinecraftServer server, long mapId) {
        if (session(server, mapId) != null || LobbyService.isPreparing(server, mapId)) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.map_already_has_match"));
        MapZoneSavedData maps = MapZoneSavedData.get(server);
        if (!maps.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_backup.could_not_read_map_data", maps.loadError()));
        MapDefinition map = maps.map(mapId);
        if (map == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_saved_data.map_does_not_exist", mapId));
        if (server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension())) == null)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_service.map_dimension_currently_unavailable", map.dimension()));
        MatchConfigSavedData templates = MatchConfigSavedData.get(server);
        if (!templates.loadError().isEmpty()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.failed_read_match_template", templates.loadError()));
        MatchConfigModel.Settings settings = templates.settings(mapId);
        List<String> errors = MatchConfigModel.validate(settings);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        try { server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BLUE_ZONE); }
        catch (RuntimeException error) { throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.blue_zone_damage_type_not_loaded_check_tacz"), error); }
        MatchRuntimeState state = STATES.computeIfAbsent(server, ignored -> new MatchRuntimeState());
        List<MatchEngine.Participant> roster = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers().stream()
                .sorted(Comparator.comparing(player -> player.getUUID().toString())).toList()) {
            if (MatchBotService.isCarpetPlayer(player)) continue;
            restoreStaleMode(player, state);
            if (!player.isAlive() || player.isRemoved() || !map.dimension().equals(player.serverLevel().dimension().location())
                    || !map.contains(player.getX(), player.getZ())) continue;
            if (state.participantRunning(player.getUUID()) || LobbyService.preparingParticipant(server, player.getUUID()))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_service.player_inside_map_already_another_match", player.getGameProfile().getName()));
            String team = settings.teamSize() > 1 && player.getTeam() != null
                    ? "team:" + player.getTeam().getName() : "solo:" + player.getUUID();
            roster.add(new MatchEngine.Participant(player.getUUID(), player.getGameProfile().getName(), team));
        }
        if (map.stagingSpawn() == null) return startRoster(server, map, settings, roster);
        if (roster.size() < settings.minPlayers() || roster.size() > settings.maxPlayers())
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_engine.participant_count_outside",
                    roster.size(), settings.minPlayers(), settings.maxPlayers()));
        MapDefinition prepared = MatchMapPreparation.prepare(map, settings, server.overworld().getRandom().nextLong());
        List<MatchEngine.Participant> frozen = List.copyOf(roster);
        MatchSpawnPreparation.begin(server, prepared, frozen.stream().map(MatchEngine.Participant::id).toList(), plan -> {
            try {
                MapResetService.requirePrepared(server, prepared);
                for (var participant : frozen) if (LobbyService.preparingParticipant(server, participant.id()))
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_service.player_inside_map_already_another_match", participant.name()));
                plan.teleportHumans(frozen.stream().map(MatchEngine.Participant::id).toList());
                startRoster(server, prepared, settings, frozen);
                plan.commit();
            } catch (RuntimeException error) {
                plan.rollback();
                throw error;
            }
            server.getPlayerList().broadcastSystemMessage(Texts.of("message.tacz_pubg.match_service.map_restored_match_started", mapId), false);
        }, error -> {
            MapResetService.ended(server, prepared);
            server.getPlayerList().getPlayers().stream().filter(player -> player.hasPermissions(2))
                    .forEach(player -> player.sendSystemMessage(Texts.of("message.tacz_pubg.match_service.match_did_not_start_map", mapId, error)));
        });
        return null; // The completion callback owns starting and announcing this prepared match.
    }
    /** The room coordinator has already registered every bot before committing this immutable roster. */
    public static MatchSession startRoster(MinecraftServer server, MapDefinition map, MatchConfigModel.Settings settings,
            List<MatchEngine.Participant> roster) {
        if (!server.isSameThread()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.start_matches_server_thread"));
        MapResetService.requirePrepared(server, map);
        if (server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension())) == null)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.lobby_service.load_map_dimension_first"));
        try { server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BLUE_ZONE); }
        catch (RuntimeException error) { throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.load_tacz_pubg_blue_zone_damage_type_first"), error); }
        MatchRuntimeState state = STATES.computeIfAbsent(server, ignored -> new MatchRuntimeState());
        for (var participant : roster) {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.id());
            if (player == null) throw new IllegalArgumentException(participant.name() + Texts.tr("message.tacz_pubg.lobby_service.online_ready"));
            restoreStaleMode(player, state);
            if (!player.isAlive() || player.isRemoved() || !map.dimension().equals(player.serverLevel().dimension().location()) || !map.contains(player.getX(), player.getZ()))
                throw new IllegalArgumentException(participant.name() + Texts.tr("message.tacz_pubg.match_service.alive_online_ready_inside_map"));
        }
        long seed = server.overworld().getRandom().nextLong();
        map = MatchMapPreparation.prepare(map, settings, seed);
        var bombingProperties = settings.bombingRules() == null ? LootConfigStore.current(server.overworld()).general().zoneSettings().bombing() : settings.bombingRules().projectile();
        MatchSession session = state.start(map, settings, roster, seed);
        try {
            for (var participant : roster) {
                ServerPlayer player = server.getPlayerList().getPlayer(participant.id());
                if (player != null) MatchInventorySnapshots.begin(player, session.sessionId());
            }
            BombingService.started(server, state.engine(map.id()), bombingProperties);
            for (var participant : roster) {
                ServerPlayer player = server.getPlayerList().getPlayer(participant.id());
                if (player == null) continue;
                if (OperatorPermissionsService.enabled(player, Permission.KEEP_GAME_MODE)) {
                    CompoundTag approval = new CompoundTag(); approval.putUUID("session", session.sessionId());
                    player.getPersistentData().put(MODE_KEEP, approval);
                    continue;
                }
                CompoundTag marker = new CompoundTag(); marker.putUUID("session", session.sessionId());
                marker.putInt("mode", player.gameMode.getGameModeForPlayer().getId());
                player.getPersistentData().put(MODE_RESTORE, marker);
                player.setGameMode(GameType.ADVENTURE);
            }
            MatchFlightService.preparing(server, state.engine(map.id()));
            MatchConfigSavedData.get(server).rememberPlayedRule(settings);
        } catch (RuntimeException error) {
            state.end(map.id()); BombingService.stop(server, session.sessionId());
            MatchFlightService.stop(server, session.sessionId());
            MatchSpectatorService.stop(server, session.sessionId());
            restoreFinishedModes(server, state);
            throw error;
        }
        return session;
    }
    public static MatchSession end(MinecraftServer server, long mapId) {
        MatchSession ended = endSession(server, mapId);
        MapResetService.ended(server, ended.map()); return ended;
    }
    /** Retire this map's live work immediately; deletion abandons its recovery journal. */
    public static void abortForMapDeletion(MinecraftServer server, MapDefinition map) {
        if (!server.isSameThread()) throw new IllegalStateException("Map deletion must run on the server thread");
        boolean retiringRound = isRunning(server, map.id());
        LobbyService.abortForMapDeletion(server, map.id());
        // Direct command starts also have staging tasks, outside the lobby coordinator.
        // Remove them now: the numeric map ID can be reused before the next server tick.
        MatchSpawnPreparation.cancelMap(server, map.id());
        if (session(server, map.id()) != null) endSession(server, map.id());
        if (retiringRound) com.example.examplemod.lootspawn.RoundLootService.end(server, map);
        else com.example.examplemod.lootspawn.LootRespawnService.cancelMap(server, map);
    }
    private static MatchSession endSession(MinecraftServer server, long mapId) {
        MatchRuntimeState state = STATES.get(server);
        if (state == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_runtime_state.map_has_no_active_match", mapId));
        MatchSession ended = state.end(mapId);
        com.example.examplemod.combat.CombatService.ended(server, ended.sessionId());
        MatchFlightService.stop(server, ended.sessionId());
        MatchSpectatorService.stop(server, ended.sessionId());
        BombingService.stop(server, ended.sessionId());
        MatchBotService.stop(server, ended.sessionId());
        restoreFinishedModes(server, state);
        return ended;
    }
    public static void pause(MinecraftServer server, long mapId) {
        MatchRuntimeState state = requireState(server);
        MatchEngine game = requireGame(state, mapId);
        game.pause(); BombingService.suspend(server, game.session().sessionId());
        MatchBotService.suspend(server, game.session().sessionId()); state.changed();
    }
    public static void resume(MinecraftServer server, long mapId) {
        MatchRuntimeState state = requireState(server); requireGame(state, mapId).resume(); state.changed();
    }
    private static MatchRuntimeState requireState(MinecraftServer server) {
        MatchRuntimeState state = STATES.get(server);
        if (state == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_service.no_active_match")); return state;
    }
    private static MatchEngine requireGame(MatchRuntimeState state, long mapId) {
        MatchEngine game = state.engine(mapId);
        if (game == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_runtime_state.map_has_no_active_match", mapId)); return game;
    }
    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        MatchSpawnPreparation.tick(server);
        if (server.tickRateManager().runsNormally()) MatchFlightService.tick(server);
        MatchBotService.tick(server);
        if (!server.tickRateManager().runsNormally()) return;
        MatchRuntimeState state = STATES.get(server);
        if (state == null) {
            MatchInventorySnapshots.restoreFinished(server);
            return;
        }
        List<MatchEngine> before = state.engines();
        for (MatchEngine game : before) for (MatchEngine.Participant entry : game.roster()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.id());
            game.presence(entry.id(), player == null ? new MatchEngine.Presence(false, false, false, null, 0, 0)
                    : new MatchEngine.Presence(true, player.isAlive(), eligible(player),
                            player.serverLevel().dimension().location(), player.getX(), player.getZ()),
                    player != null && MatchFlightService.onboard(server, player.getUUID()));
        }
        MatchSpectatorService.tick(server, before);
        boolean pulse = state.advanceTick();
        for (MatchEngine game : before) if (game.finished()) {
            com.example.examplemod.combat.CombatService.ended(server, game.session().sessionId());
            MatchFlightService.stop(server, game.session().sessionId());
            for (MatchEngine.Participant entry : game.roster()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.id());
                if (player != null) player.sendSystemMessage(Texts.of("message.tacz_pubg.match_service.match_map_ended", game.session().map().id(), game.status().result()));
            }
            MatchBotService.stop(server, game.session().sessionId());
            BombingService.stop(server, game.session().sessionId());
            MatchSpectatorService.stop(server, game.session().sessionId());
            MapResetService.ended(server, game.session().map());
        }
        restoreFinishedModes(server, state);
        BombingService.tick(server, state.engines());
        if (!pulse) return;
        DamageSource source = new DamageSource(server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BLUE_ZONE));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (OperatorPermissionsService.enabled(player, Permission.IMMUNE_BLUE_ZONE)) continue;
            double damage = state.damageFor(player.getUUID(), player.serverLevel().dimension().location(),
                    player.getX(), player.getZ(), eligible(player));
            if (damage > 0) player.hurt(source, (float) damage);
        }
    }
    private static boolean eligible(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && ((!player.isCreative() && !player.isSpectator())
                || approvedKeptMode(player));
    }
    private static boolean approvedKeptMode(ServerPlayer player) {
        if (!OperatorPermissionsService.operator(player)) return false;
        CompoundTag approval = player.getPersistentData().getCompound(MODE_KEEP);
        if (!approval.hasUUID("session")) return false;
        MatchRuntimeState state = STATES.get(player.server);
        return state != null && state.hasActiveRosterSession(player.getUUID(), approval.getUUID("session"));
    }
    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MatchEngine game = participantGame(player.server, player.getUUID());
            if (game != null && game.alive(player.getUUID())) {
                UUID credit = com.example.examplemod.combat.CombatService.finalDeath(player, game, event.getSource());
                MatchSpectatorService.death(player, game, credit);
            }
            eliminate(player);
        }
    }
    /** Administrative roster removal; it never manufactures a vanilla death or inventory drops. */
    public static void eliminate(ServerPlayer player) {
        MatchRuntimeState state = STATES.get(player.server); if (state == null) return;
        boolean changed = false;
        for (MatchEngine game : state.engines()) changed |= game.eliminate(player.getUUID());
        if (changed) state.changed(); // Judge victory only after the entire next tick's presence batch.
    }
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        MatchEngine victimGame = participantGame(victim.server, victim.getUUID());
        if (victimGame != null && victimGame.alive(victim.getUUID())
                && ("preparing".equals(victimGame.status().stage()) && !(event.getSource().getEntity() instanceof ServerPlayer)
                    || MatchFlightService.onboard(victim.server, victim.getUUID()))) {
            event.setCanceled(true); return;
        }
        // DamageSource.getEntity is the owner of both vanilla and TaCZ projectiles, not the bullet entity.
        ServerPlayer attacker = event.getSource().getEntity() instanceof ServerPlayer source ? source : null;
        boolean operator = attacker != null && OperatorPermissionsService.operator(attacker);
        OperatorPermissions permissions = operator ? OperatorPermissionsService.current(victim.server) : OperatorPermissions.defaults();
        boolean bot = MatchBotService.owned(victim.server, victim.getUUID());
        boolean targetPrivilege = operator && (bot ? permissions.damageBots() : permissions.damagePlayers());
        MatchRuntimeState state = STATES.get(victim.server);
        if (MatchBotService.preparingBody(victim.server, victim.getUUID())) {
            if (!(targetPrivilege && permissions.bypassPreparationProtection())
                    || state != null && state.blocksPlayerDamage(attacker.getUUID(), victim.getUUID(), targetPrivilege,
                            permissions.bypassFriendlyFire(), permissions.bypassPreparationProtection())) event.setCanceled(true);
            return;
        }
        if (attacker == null) return;
        if (state != null && state.blocksPlayerDamage(attacker.getUUID(), victim.getUUID(), targetPrivilege,
                operator && permissions.bypassFriendlyFire(), operator && permissions.bypassPreparationProtection())) event.setCanceled(true);
    }
    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MatchSpectatorService.logout(player);
        if (MatchBotService.isCarpetPlayer(player)) eliminate(player);
        MatchRuntimeState state = STATES.get(player.server);
        if (state != null) for (MatchEngine game : state.engines())
            game.presence(player.getUUID(), new MatchEngine.Presence(false, false, false, null, 0, 0));
    }
    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Recover a current-round camera marker before stale-mode cleanup examines it.
            MatchSpectatorService.login(player);
            restoreStaleMode(player, STATES.get(player.server));
        }
    }
    private static void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original && event.getEntity() instanceof ServerPlayer replacement) {
            MatchSpectatorService.clonePlayer(original, replacement);
            MatchInventorySnapshots.clonePlayer(original, replacement);
        }
        if (event.getOriginal().getPersistentData().contains(MODE_RESTORE, Tag.TAG_COMPOUND))
            event.getEntity().getPersistentData().put(MODE_RESTORE, event.getOriginal().getPersistentData().getCompound(MODE_RESTORE).copy());
        if (event.getOriginal().getPersistentData().contains(MODE_KEEP, Tag.TAG_COMPOUND))
            event.getEntity().getPersistentData().put(MODE_KEEP, event.getOriginal().getPersistentData().getCompound(MODE_KEEP).copy());
    }
    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.isEndConquered()) eliminate(player);
        MatchRuntimeState state = STATES.get(player.server); restoreStaleMode(player, state);
        if (state != null) for (MatchEngine game : state.engines())
            if (game.contains(player.getUUID()) && !game.alive(player.getUUID()) && MatchSpectatorService.shouldSpectate(player, game)
                    && !MatchBotService.isCarpetPlayer(player)) enterSpectator(player, game);
        MatchInventorySnapshots.restoreIfStale(player);
    }
    static void enterSpectator(ServerPlayer player, MatchEngine game) {
        if (player.isSpectator()) return;
        if (player.getPersistentData().contains(MODE_RESTORE, Tag.TAG_COMPOUND)) {
            player.setGameMode(GameType.SPECTATOR); return; // Keep the original pre-match-service mode through respawn.
        }
        GameType previous = player.gameMode.getGameModeForPlayer();
        if (player.setGameMode(GameType.SPECTATOR)) {
            CompoundTag marker = new CompoundTag();
            marker.putUUID("session", game.session().sessionId()); marker.putInt("mode", previous.getId());
            player.getPersistentData().put(MODE_RESTORE, marker);
        }
    }
    private static void restoreFinishedModes(MinecraftServer server, MatchRuntimeState state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) restoreStaleMode(player, state);
        MatchInventorySnapshots.restoreFinished(server);
    }
    private static void restoreStaleMode(ServerPlayer player, @Nullable MatchRuntimeState state) {
        MatchSpectatorService.clearIfStale(player);
        CompoundTag data = player.getPersistentData();
        CompoundTag approval = data.getCompound(MODE_KEEP);
        if (data.contains(MODE_KEEP) && (!approval.hasUUID("session") || state == null || state.sessions().stream()
                .noneMatch(active -> active.sessionId().equals(approval.getUUID("session"))))) data.remove(MODE_KEEP);
        if (!data.contains(MODE_RESTORE, Tag.TAG_COMPOUND)) return;
        CompoundTag marker = data.getCompound(MODE_RESTORE);
        UUID session = marker.hasUUID("session") ? marker.getUUID("session") : null;
        if (session != null && state != null && state.sessions().stream().anyMatch(active -> active.sessionId().equals(session))) return;
        int previous = marker.getInt("mode");
        if ((player.isSpectator() || player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) && marker.contains("mode", Tag.TAG_INT) && previous >= 0 && previous <= 3)
            player.setGameMode(GameType.byId(previous));
        // An external administrator mode change is preserved: only our own marker is removed.
        data.remove(MODE_RESTORE);
    }
    private static void onServerStopped(ServerStoppedEvent event) {
        MatchFlightService.stopped(event.getServer());
        MatchSpawnPreparation.stopped(event.getServer());
        LobbyService.stopped(event.getServer());
        MatchBotService.stopped(event.getServer());
        BombingService.stopped(event.getServer());
        MatchSpectatorService.stopped(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) restoreStaleMode(player, null);
        STATES.remove(event.getServer());
        MatchInventorySnapshots.restoreFinished(event.getServer());
    }
}
