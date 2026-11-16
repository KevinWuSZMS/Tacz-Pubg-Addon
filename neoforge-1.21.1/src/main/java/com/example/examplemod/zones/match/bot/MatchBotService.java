package com.example.examplemod.zones.match.bot;

import com.example.examplemod.localization.Texts;

import carpet.fakes.ServerPlayerInterface;
import carpet.patches.EntityPlayerMPFake;
import carpet.patches.NetHandlerPlayServerFake;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchEngine.Participant;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.MatchSession;
import com.example.examplemod.zones.match.MatchSpawnPreparation;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.tacz.guns.api.entity.IGunOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Owns explicit Carpet bodies. Preparation is a cancellable server-tick queue with reserved slots. */
public final class MatchBotService {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final int PREPARE_TIMEOUT_TICKS = 400;
    private static final int BODY_SETTLE_TICKS = 60;
    public static final Set<String> BEHAVIORS = Set.of("auto", "seek", "loot", "attack", "evade", "follow", "guard", "patrol", "move", "hold", "circle");
    private MatchBotService() {}

    public record BotSpec(UUID plannedId, String name, String teamId, String loadout, String difficulty) {
        public BotSpec {
            Objects.requireNonNull(plannedId); Objects.requireNonNull(name); Objects.requireNonNull(teamId);
            if (teamId.isBlank() || teamId.length() > 96) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.assign_valid_team_bot"));
            if (!BotRules.LOADOUTS.contains(loadout)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.choose_rifle_smg_scavenging_equipment"));
            BotRules.skill(difficulty);
        }
    }
    public record PreparedBot(UUID plannedId, UUID actualId, String name, String teamId) {}
    public record Preparation(UUID requestToken, long mapId, List<PreparedBot> bots, String error) {
        public Preparation { bots = List.copyOf(bots); Objects.requireNonNull(error); }
    }
    private static final class Request {
        final UUID token = UUID.randomUUID(); final MapDefinition map; final List<BotSpec> specs;
        final Map<UUID, Vec3> spawnPositions;
        final Consumer<Preparation> callback; int deadline; final List<PreparedBot> ready = new ArrayList<>();
        int next, lastCreated; boolean notified;
        Request(MapDefinition map, List<BotSpec> specs, Map<UUID, Vec3> spawnPositions, Consumer<Preparation> callback, int tick) {
            this.map = map; this.specs = List.copyOf(specs); this.callback = callback; deadline = tick + PREPARE_TIMEOUT_TICKS;
            this.spawnPositions = Map.copyOf(spawnPositions);
        }
    }
    private static final class Owned {
        final UUID request; EntityPlayerMPFake body; final BotBrain brain;
        final MapDefinition map; final Vec3 stagingPosition;
        UUID session; List<Participant> roster = List.of(); boolean removing, awaitingRespawn, parked;
        Owned(UUID request, EntityPlayerMPFake body, BotBrain brain, MapDefinition map, Vec3 stagingPosition) {
            this.request = request; this.body = body; this.brain = brain;
            this.map = map; this.stagingPosition = stagingPosition;
        }
    }
    private static final class State {
        final Map<UUID, Request> requests = new LinkedHashMap<>();
        final Map<UUID, Owned> owned = new LinkedHashMap<>();
        int cursor;
    }
    public static boolean available() { return ModList.get().isLoaded("carpet"); }
    public static boolean isCarpetPlayer(ServerPlayer player) { return available() && player instanceof EntityPlayerMPFake; }
    public static boolean owned(MinecraftServer server, UUID id) {
        State state = STATES.get(server); Owned owned = state == null ? null : state.owned.get(id);
        return owned != null && !owned.removing && server.getPlayerList().getPlayer(id) == owned.body;
    }
    public static boolean preparingBody(MinecraftServer server, UUID id) {
        State state = STATES.get(server); Owned owned = state == null ? null : state.owned.get(id);
        return owned != null && !owned.removing && owned.session == null && server.getPlayerList().getPlayer(id) == owned.body;
    }
    /** Called after vanilla death has completed, before Carpet resets health and disconnects its body. */
    public static boolean retainAfterDeath(ServerPlayer player) {
        State state = STATES.get(player.server); Owned owned = state == null ? null : state.owned.get(player.getUUID());
        if (owned == null || owned.removing || owned.body != player || owned.session == null) return false;
        var game = MatchService.participantGame(player.server, player.getUUID());
        if (game == null || !owned.session.equals(game.session().sessionId()) || game.alive(player.getUUID())) return false;
        owned.awaitingRespawn = true; owned.parked = false; owned.brain.suspend();
        return true;
    }
    /** Uses the validated original staging position and bypasses beds/respawn-anchor consumption. */
    public static DimensionTransition respawnTransition(ServerPlayer player, boolean keepEverything) {
        if (keepEverything) return null;
        State state = STATES.get(player.server); Owned owned = state == null ? null : state.owned.get(player.getUUID());
        if (owned == null || owned.removing || owned.body != player || owned.session == null
                || !owned.awaitingRespawn && player.isAlive()) return null;
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, owned.map.dimension()));
        return level == null ? null : new DimensionTransition(level, owned.stagingPosition, Vec3.ZERO,
                player.getYRot(), 0, DimensionTransition.DO_NOTHING);
    }
    public static void suspend(ServerPlayer player) {
        State state = STATES.get(player.server); Owned owned = state == null ? null : state.owned.get(player.getUUID());
        if (owned != null && !owned.removing && owned.body == player) owned.brain.suspend();
    }
    /** Includes external Carpet players and queued creation reservations. */
    public static int reservedCount(MinecraftServer server) {
        int online = (int) server.getPlayerList().getPlayers().stream().filter(MatchBotService::isCarpetPlayer).count();
        State state = STATES.get(server);
        return online + (state == null ? 0 : state.requests.values().stream().mapToInt(r -> r.specs.size() - r.next).sum());
    }
    public static int preparedCount(MinecraftServer server, UUID requestToken) {
        State state = STATES.get(server); Request request = state == null ? null : state.requests.get(requestToken);
        return request == null ? 0 : request.ready.size();
    }
    public static List<String> names(MinecraftServer server) {
        State state = STATES.get(server); if (state == null) return List.of();
        return state.owned.values().stream().filter(owned -> !owned.removing
                && server.getPlayerList().getPlayer(owned.body.getUUID()) == owned.body)
                .map(owned -> owned.body.getGameProfile().getName()).sorted().toList();
    }
    public static String status(MinecraftServer server, String name) {
        Owned owned = named(server, name);
        String phase = owned.session == null ? Texts.tr("message.tacz_pubg.match_bot_service.preparing_slots") : MatchService.matches(server).stream()
                .filter(session -> session.sessionId().equals(owned.session)).findFirst()
                .map(session -> MatchService.status(server, session.map().id()).stage()).orElse(Texts.tr("message.tacz_pubg.match_bot_service.match_cleanup"));
        return name + Texts.tr("message.tacz_pubg.match_bot_service.phase") + phase + "；" + owned.brain.description();
    }
    public static String behavior(MinecraftServer server, String name, String behavior, Vec3 point, UUID followed) {
        requireThread(server); Owned owned = named(server, name);
        if (!BEHAVIORS.contains(behavior)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.select_valid_bot_behavior"));
        MapDefinition map = owned.session == null ? requireState(server).requests.get(owned.request).map
                : MatchService.matches(server).stream().filter(session -> session.sessionId().equals(owned.session)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.select_managed_bot_preparing_match"))).map();
        if ("move".equals(behavior) && (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || !BotTerrain.standing(owned.body.serverLevel(), map, (int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z))))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.select_loaded_target_position_inside_map_clearance_ground"));
        if ("follow".equals(behavior)) {
            Participant self = owned.roster.stream().filter(entry -> entry.id().equals(owned.body.getUUID())).findFirst().orElse(null);
            Participant target = owned.roster.stream().filter(entry -> entry.id().equals(followed)).findFirst().orElse(null);
            ServerPlayer player = followed == null ? null : server.getPlayerList().getPlayer(followed);
            if (owned.session == null || self == null || target == null || player == null || target.id().equals(self.id())
                    || !target.teamId().equals(self.teamId()) || !MatchService.alive(server, map.id(), followed)
                    || player.serverLevel() != owned.body.serverLevel()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.after_match_start_select_living_teammate_match"));
        }
        owned.brain.mode(behavior, point, followed); return status(server, name);
    }
    public static String fire(MinecraftServer server, String name, boolean allowed) {
        requireThread(server); named(server, name).brain.fire(allowed); return status(server, name);
    }
    public static String reload(MinecraftServer server, String name) {
        requireThread(server); named(server, name).brain.reload(); return name + Texts.tr("message.tacz_pubg.match_bot_service.native_reload_request_recorded_will_execute_while_match");
    }
    public static String despawn(MinecraftServer server, String name) {
        requireThread(server); State state = requireState(server); Owned owned = named(server, name);
        Request request = state.requests.get(owned.request);
        if (owned.session == null && request != null) fail(server, state, request, Texts.tr("message.tacz_pubg.match_bot_service.administrator_cancelled_bot_preparation_match"));
        else remove(owned);
        return name + Texts.tr("message.tacz_pubg.match_bot_service.cleanup_started");
    }
    private static Owned named(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name); State state = STATES.get(server);
        Owned owned = player == null || state == null ? null : state.owned.get(player.getUUID());
        if (owned == null || owned.removing || owned.body != player) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.select_online_bot_created_by_addon_use_pubgplayer"));
        return owned;
    }
    public static UUID prepare(MinecraftServer server, MapDefinition map, List<BotSpec> specs, Consumer<Preparation> callback) {
        return prepare(server, map, specs, Map.of(), callback);
    }
    public static UUID prepare(MinecraftServer server, MapDefinition map, List<BotSpec> specs,
            Map<UUID, Vec3> spawnPositions, Consumer<Preparation> callback) {
        requireThread(server);
        if (!available()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.load_carpet_neoforged_create_bots"));
        if (specs.size() > BotRules.MAX_BOTS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.most_bots_can_created_per_match"));
        HashSet<UUID> ids = new HashSet<>();
        for (BotSpec spec : specs) if (!ids.add(spec.plannedId())) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.bot_slot_identifiers_unique"));
        BotRules.reserve(reservedCount(server), specs.size());
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        if (state.requests.values().stream().anyMatch(request -> request.map.id() == map.id()))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.bot_slots_map_being_prepared_wait_completion"));
        if (!spawnPositions.isEmpty() && specs.stream().anyMatch(spec -> !spawnPositions.containsKey(spec.plannedId())))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_staging.changed"));
        Request request = new Request(map, specs, spawnPositions, Objects.requireNonNull(callback), server.getTickCount());
        state.requests.put(request.token, request); return request.token;
    }
    public static void bind(MinecraftServer server, UUID requestToken, MatchSession session, List<Participant> roster) {
        requireThread(server); State state = requireState(server); Request request = state.requests.get(requestToken);
        if (request == null || !request.notified || request.next != request.specs.size()
                || request.map.id() != session.map().id()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.wait_match_s_bots_finish_preparing"));
        if (roster.size() > BotRules.MAX_PARTICIPANTS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.most_participant_slots_allowed_per_match"));
        MatchSession currentSession = MatchService.session(server, session.map().id());
        if (currentSession == null || !currentSession.sessionId().equals(session.sessionId()))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.freeze_match_s_official_roster_first"));
        List<Participant> frozen = MatchService.roster(server, session.map().id());
        if (!new HashSet<>(frozen.stream().map(Participant::id).toList()).equals(new HashSet<>(roster.stream().map(Participant::id).toList()))
                || frozen.size() != roster.size()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.bot_control_roster_match_official_match_roster"));
        Map<UUID, Participant> participants = new LinkedHashMap<>();
        for (Participant participant : frozen) participants.put(participant.id(), participant);
        for (PreparedBot bot : request.ready) {
            Participant participant = participants.get(bot.actualId()); Owned owned = state.owned.get(bot.actualId());
            if (owned == null || owned.removing || server.getPlayerList().getPlayer(bot.actualId()) != owned.body
                    || participant == null || !(participant.teamId().equals(bot.teamId()) || participant.teamId().equals("solo:" + bot.actualId())))
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.prepare_bot_slots_again_correct_match_teams"));
        }
        for (PreparedBot bot : request.ready) {
            Owned owned = state.owned.get(bot.actualId()); owned.session = session.sessionId(); owned.roster = List.copyOf(frozen);
            owned.brain.team(participants.get(bot.actualId()).teamId());
        }
        state.requests.remove(requestToken);
    }
    public static void cancel(MinecraftServer server, UUID requestToken) {
        requireThread(server); State state = STATES.get(server); if (state == null) return;
        state.requests.remove(requestToken);
        for (Owned owned : new ArrayList<>(state.owned.values())) if (owned.request.equals(requestToken)) remove(owned);
    }
    public static void stop(MinecraftServer server, UUID sessionId) {
        requireThread(server); State state = STATES.get(server); if (state == null) return;
        for (Owned owned : new ArrayList<>(state.owned.values())) if (sessionId.equals(owned.session)) remove(owned);
    }
    public static void suspend(MinecraftServer server, UUID sessionId) {
        requireThread(server); State state = STATES.get(server); if (state == null) return;
        for (Owned owned : state.owned.values()) if (!owned.removing && sessionId.equals(owned.session)) owned.brain.suspend();
    }
    public static void tick(MinecraftServer server) {
        State state = STATES.get(server); if (state == null) return; requireThread(server);
        state.owned.values().removeIf(owned -> server.getPlayerList().getPlayer(owned.body.getUUID()) != owned.body);
        if (!server.tickRateManager().runsNormally()) {
            for (Request request : state.requests.values()) { request.deadline++; request.lastCreated++; }
            for (Owned owned : state.owned.values()) if (!owned.removing) owned.brain.suspend();
            return;
        }
        int creates = 0;
        for (Request request : new ArrayList<>(state.requests.values())) {
            if (!state.requests.containsKey(request.token)) continue;
            if (server.getTickCount() >= request.deadline) { fail(server, state, request, Texts.tr("message.tacz_pubg.match_bot_service.bot_preparation_timed_out_check_loaded_safe_positions")); continue; }
            if (!request.notified && request.next < request.specs.size() && creates == 0) {
                creates++;
                try {
                    BotRules.reserve(reservedCount(server), 0);
                    PreparedBot bot = create(server, state, request, request.specs.get(request.next));
                    request.ready.add(bot); request.next++; request.lastCreated = server.getTickCount();
                } catch (RuntimeException error) {
                    LOG.warn("PUBG bot preparation failed for map {}", request.map.id(), error);
                    fail(server, state, request, message(error)); continue;
                }
            }
            if (!request.notified && request.next == request.specs.size()
                    && (request.specs.isEmpty() || server.getTickCount() - request.lastCreated >= BODY_SETTLE_TICKS)) {
                request.notified = true;
                try { request.callback.accept(new Preparation(request.token, request.map.id(), request.ready, "")); }
                catch (RuntimeException error) { cancel(server, request.token); LOG.warn("PUBG bot start callback failed", error); }
            }
        }
        BotRules.Budget budget = new BotRules.Budget(96, 384);
        List<Owned> bots = new ArrayList<>(state.owned.values());
        for (int offset = 0; offset < bots.size(); offset++) {
            Owned owned = bots.get((offset + state.cursor) % bots.size()); if (owned.removing) continue;
            MatchSession session = owned.session == null ? null : MatchService.matches(server).stream()
                    .filter(match -> match.sessionId().equals(owned.session)).findFirst().orElse(null);
            if (owned.session != null && session == null) { remove(owned); continue; }
            if (session != null && !MatchService.alive(server, session.map().id(), owned.body.getUUID())) {
                try { parkEliminated(server, owned); }
                catch (RuntimeException error) { remove(owned); LOG.warn("PUBG bot spectator respawn failed: {}", owned.body.getGameProfile().getName(), error); }
                continue;
            }
            if (session == null) owned.brain.suspend();
            else try { owned.brain.tick(server, session, owned.roster, budget); }
            catch (RuntimeException error) { remove(owned); LOG.warn("PUBG bot stopped after controller error: {}", owned.body.getGameProfile().getName(), error); }
        }
        state.cursor = bots.isEmpty() ? 0 : (state.cursor + 1) % bots.size();
    }
    private static void parkEliminated(MinecraftServer server, Owned owned) {
        owned.brain.suspend();
        if (owned.awaitingRespawn || !owned.body.isAlive()) {
            ServerPlayer replacement = server.getPlayerList().respawn(owned.body, false, Entity.RemovalReason.KILLED);
            replacement.connection.player = replacement;
            if (!(replacement instanceof EntityPlayerMPFake fake))
                throw new IllegalStateException("Carpet did not preserve a match bot during respawn");
            owned.body = fake; owned.brain.rebind(fake);
            fake.getPersistentData().putUUID("tacz_pubg_bot_owner", owned.request);
            owned.awaitingRespawn = false; owned.parked = false;
        }
        if (!owned.body.isSpectator()) owned.body.setGameMode(GameType.SPECTATOR);
        if (!owned.parked) {
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, owned.map.dimension()));
            if (level == null) return;
            owned.body.stopRiding(); owned.body.stopUsingItem(); owned.body.setCamera(owned.body);
            Vec3 point = owned.stagingPosition;
            owned.body.teleportTo(level, point.x, point.y, point.z, owned.body.getYRot(), 0);
            owned.body.setDeltaMovement(Vec3.ZERO);
            owned.parked = true;
        }
    }
    private static PreparedBot create(MinecraftServer server, State state, Request request, BotSpec spec) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, request.map.dimension()));
        if (level == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.load_map_s_dimension_first"));
        Vec3 reserved = request.spawnPositions.get(spec.plannedId());
        if (reserved != null && !MatchSpawnPreparation.safePosition(level, request.map, reserved))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_staging.changed"));
        BlockPos feet = reserved == null ? findSpawn(server, level, request.map, request.next) : BlockPos.containing(reserved);
        if (feet == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.match_bot_service.prepare_loaded_positions_inside_map_clearance_ground_support"));
        String name = BotRules.botName(request.token, request.next + 1);
        UUID expectedId = UUIDUtil.createOfflinePlayerUUID(name);
        if (server.getPlayerList().getPlayerByName(name) != null || server.getPlayerList().getPlayer(expectedId) != null)
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.bot_name_already_online_prepare_match_again"));
        EntityPlayerMPFake fake = EntityPlayerMPFake.respawnFake(level, new GameProfile(expectedId, name), ClientInformation.createDefault());
        // This public Carpet constructor bypasses profile fetching. Registration and every mutation run on the server thread.
        float initialYaw = request.next * 137.5F;
        fake.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, initialYaw, 0);
        fake.fixStartingPosition = () -> fake.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5, initialYaw, 0);
        Owned owned = new Owned(request.token, fake, new BotBrain(fake, spec, request.map), request.map,
                new Vec3(feet.getX() + .5, feet.getY(), feet.getZ() + .5));
        UUID constructedId = fake.getUUID(); state.owned.put(constructedId, owned);
        try {
            server.getPlayerList().placeNewPlayer(NetHandlerPlayServerFake.DUMMY_CONNECTION, fake,
                    new CommonListenerCookie(fake.getGameProfile(), 0, fake.clientInformation(), false));
            if (server.getPlayerList().getPlayer(fake.getUUID()) != fake) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.check_server_accepts_new_bot_players"));
            if (!fake.getUUID().equals(constructedId)) { state.owned.remove(constructedId); state.owned.put(fake.getUUID(), owned); }
            fake.teleportTo(level, feet.getX() + .5, feet.getY(), feet.getZ() + .5, request.next * 137.5F, 0);
            fake.setGameMode(GameType.ADVENTURE); fake.getAbilities().flying = false; fake.getAbilities().invulnerable = false;
            fake.setHealth(fake.getMaxHealth()); fake.getFoodData().setFoodLevel(20); fake.getInventory().clearContent();
            fake.getPersistentData().putUUID("tacz_pubg_bot_owner", request.token);
            owned.brain.suspend();
            return new PreparedBot(spec.plannedId(), fake.getUUID(), fake.getGameProfile().getName(), spec.teamId());
        } catch (RuntimeException error) { remove(owned); throw error; }
    }
    private static BlockPos findSpawn(MinecraftServer server, ServerLevel level, MapDefinition map, int slot) {
        List<Vec3> anchors = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (!isCarpetPlayer(player)
                && player.serverLevel() == level && map.contains(player.getX(), player.getZ())) anchors.add(player.position());
        anchors.add(new Vec3((map.minX() + map.maxX()) / 2, 0, (map.minZ() + map.maxZ()) / 2));
        for (Vec3 anchor : anchors) for (int attempt = 0; attempt < 96; attempt++) {
            double angle = (slot * 137.5 + attempt * 47) * Math.PI / 180;
            double radius = attempt == 95 ? 0 : 3 + (attempt / 12) * 3;
            int x = (int) Math.floor(anchor.x + Math.cos(angle) * radius), z = (int) Math.floor(anchor.z + Math.sin(angle) * radius);
            BlockPos pos = BotTerrain.surface(level, map, x, z); if (pos == null) continue;
            boolean occupied = server.getPlayerList().getPlayers().stream().anyMatch(player -> player.serverLevel() == level
                    && player.position().distanceToSqr(Vec3.atBottomCenterOf(pos)) < 2.25);
            if (!occupied) return pos;
        }
        return null;
    }
    private static void fail(MinecraftServer server, State state, Request request, String error) {
        boolean notify = !request.notified; cancel(server, request.token);
        if (notify) try { request.callback.accept(new Preparation(request.token, request.map.id(), List.of(), error)); }
        catch (RuntimeException callbackError) { LOG.warn("PUBG bot failure callback failed", callbackError); }
    }
    private static String message(RuntimeException error) { return error.getMessage() == null ? Texts.tr("message.tacz_pubg.match_bot_service.prepare_bot_slots_again") : error.getMessage(); }
    private static void remove(Owned owned) {
        if (owned.removing) return; owned.removing = true;
        try { owned.brain.suspend(); } catch (RuntimeException error) { LOG.debug("Stopping bot gun state", error); }
        ServerPlayer current = owned.body.server.getPlayerList().getPlayer(owned.body.getUUID());
        if (current == owned.body) owned.body.kill(Texts.of("message.tacz_pubg.match_bot_service.pubg_match_bot_cleanup_complete"));
    }
    public static void stopped(MinecraftServer server) {
        State state = STATES.remove(server); if (state == null) return;
        state.requests.clear(); for (Owned owned : state.owned.values()) remove(owned); state.owned.clear();
    }
    private static State requireState(MinecraftServer server) {
        State state = STATES.get(server); if (state == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.prepare_match_s_bots_first")); return state;
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException(Texts.tr("message.tacz_pubg.match_bot_service.manage_bot_slots_server_thread"));
    }
}
