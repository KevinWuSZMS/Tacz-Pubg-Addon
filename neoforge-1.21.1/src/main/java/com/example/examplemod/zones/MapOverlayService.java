package com.example.examplemod.zones;

import com.example.examplemod.zones.MapOverlayPayloads.MapSnapshot;
import com.example.examplemod.zones.MapOverlayPayloads.TeamSnapshot;
import com.example.examplemod.zones.MapOverlayPayloads.Teammate;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchCirclePreview;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.MatchStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.LongFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** Sends the relevant active-match snapshot, or the viewer's preview map, and actual teammates. */
public final class MapOverlayService {
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final int[] COLORS = {0xFFFFD84A, 0xFF5FD2FF, 0xFFFF79BE, 0xFF7FE280, 0xFFC7A7FF, 0xFFFFA65E};
    private MapOverlayService() {}

    public static void register(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post event) -> {
            if (event.getServer().getTickCount() % 10 == 0)
                for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) send(player, false);
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) send(player, true);
        });
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) send(player, true);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                State state = STATES.get(player.server);
                if (state != null) state.viewers.remove(player.getUUID());
            }
        });
        bus.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() != null) send(event.getPlayer(), true);
            else for (ServerPlayer player : event.getPlayerList().getPlayers()) send(player, true);
        });
        bus.addListener((ServerStoppedEvent event) -> STATES.remove(event.getServer()));
    }

    /** Explicit editor selection takes priority, then the smallest containing map, then oldest ID. */
    @Nullable public static MapDefinition choose(List<MapDefinition> maps, @Nullable MapDefinition selected,
            net.minecraft.resources.ResourceLocation dimension, double x, double z) {
        if (selected != null && dimension.equals(selected.dimension())) return selected;
        return maps.stream().filter(map -> dimension.equals(map.dimension()) && map.contains(x, z))
                .min(Comparator.comparingDouble(MapDefinition::area).thenComparingLong(MapDefinition::id)).orElse(null);
    }

    /** Match damage wins over editor selection; only the supplied immutable active snapshots qualify. */
    @Nullable static MapDefinition chooseRuntime(List<MapDefinition> maps, List<MapDefinition> activeMaps,
            @Nullable MapDefinition selected, net.minecraft.resources.ResourceLocation dimension, double x, double z) {
        if (dimension == null || !Double.isFinite(x) || !Double.isFinite(z)) return null;
        MapDefinition damaging = activeMaps.stream().filter(map -> map.isBlue(dimension, x, z))
                .min(Comparator.comparingDouble(MapDefinition::blueDamagePerSecond).reversed()
                        .thenComparingLong(MapDefinition::id)).orElse(null);
        if (damaging != null) return damaging;
        MapDefinition activeHere = activeMaps.stream()
                .filter(map -> dimension.equals(map.dimension()) && map.contains(x, z))
                .min(Comparator.comparingLong(MapDefinition::id)).orElse(null);
        return activeHere == null ? choose(maps, selected, dimension, x, z) : activeHere;
    }

    private static void send(ServerPlayer player, boolean force) {
        State state = STATES.computeIfAbsent(player.server, ignored -> new State());
        Viewer viewer = state.viewers.computeIfAbsent(player.getUUID(), ignored -> new Viewer());
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        var settingsStore = com.example.examplemod.lootconfig.LootConfigStore.get(player.serverLevel());
        long settingsRevision = settingsStore.loadError().isEmpty() ? settingsStore.revision() : -1;
        ZoneSettings presentation = settingsStore.loadError().isEmpty()
                ? settingsStore.document().general().zoneSettings() : ZoneSettings.defaults();
        long matchRevision = com.example.examplemod.zones.match.MatchService.revision(player.server);
        if (state.revision != data.revision() || state.matchRevision != matchRevision || state.settingsRevision != settingsRevision) {
            state.snapshots = new SnapshotCache(data.loadError().isEmpty() ? data.maps() : List.of(),
                    MatchService.activeMaps(player.server), mapId -> MatchService.status(player.server, mapId), presentation);
            state.revision = data.revision(); state.matchRevision = matchRevision; state.settingsRevision = settingsRevision;
        }
        var dimension = player.serverLevel().dimension().location();
        MapDefinition selected = MapZoneCommands.selectedMap(player);
        if (selected != null) {
            long selectedId = selected.id();
            selected = state.snapshots.maps.stream().filter(map -> map.id() == selectedId).findFirst().orElse(selected);
        }
        MapDefinition map = data.loadError().isEmpty()
                ? chooseRuntime(state.snapshots.maps, state.snapshots.activeMaps, selected, dimension, player.getX(), player.getZ()) : null;
        long mapId = map == null ? 0 : map.id();
        if (force || viewer.revision != data.revision() || viewer.matchRevision != matchRevision || viewer.settingsRevision != settingsRevision
                || viewer.mapId != mapId || !dimension.equals(viewer.dimension)) {
            MapSnapshot snapshot = state.snapshots.packet(++viewer.mapSequence, mapId);
            PacketDistributor.sendToPlayer(player, snapshot == null
                    ? new MapSnapshot(viewer.mapSequence, dimension, "") : snapshot);
            long catalogRevision = ++viewer.catalogSequence;
            List<MapDefinition> all = state.snapshots.maps;
            if (all.isEmpty()) PacketDistributor.sendToPlayer(player,
                    new MapOverlayPayloads.CatalogPart(catalogRevision, 0, 0, null));
            for (int i = 0; i < all.size(); i++) {
                var other = all.get(i);
                PacketDistributor.sendToPlayer(player, new MapOverlayPayloads.CatalogPart(catalogRevision, i, all.size(),
                        state.snapshots.packet(catalogRevision, other.id())));
            }
            viewer.revision = data.revision(); viewer.matchRevision = matchRevision; viewer.settingsRevision = settingsRevision;
            viewer.mapId = mapId; viewer.dimension = dimension;
        }
        PacketDistributor.sendToPlayer(player, new TeamSnapshot(++viewer.teamSequence, dimension, teammates(player)));
    }

    /** Shared arbitration for phase HUD and geometry, including overlapping active maps. */
    @Nullable public static MapDefinition currentMapFor(ServerPlayer player) {
        var participation = com.example.examplemod.zones.match.MatchService.participantGame(player.server, player.getUUID());
        if (participation != null && !participation.finished()
                && participation.session().map().dimension().equals(player.serverLevel().dimension().location()))
            return participation.session().map();
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        if (!data.loadError().isEmpty()) return null;
        MapDefinition selected = MapZoneCommands.selectedMap(player);
        if (selected != null) {
            var active = com.example.examplemod.zones.match.MatchService.activeMap(player.server, selected.id());
            if (active != null) selected = active;
        }
        return chooseRuntime(data.maps(), com.example.examplemod.zones.match.MatchService.activeMaps(player.server),
                selected, player.serverLevel().dimension().location(), player.getX(), player.getZ());
    }

    private static List<Teammate> teammates(ServerPlayer viewer) {
        var game = com.example.examplemod.zones.match.MatchService.participantGame(viewer.server, viewer.getUUID());
        if (game != null) {
            String team = game.roster().stream().filter(member -> member.id().equals(viewer.getUUID()))
                    .findFirst().orElseThrow().teamId();
            var frozen = game.roster().stream().filter(member -> member.teamId().equals(team)).toList();
            var dimension = viewer.serverLevel().dimension().location(); List<Teammate> result = new ArrayList<>();
            for (int i = 0; i < frozen.size() && result.size() < MapOverlayPayloads.MAX_TEAMMATES; i++) {
                var entrant = frozen.get(i);
                ServerPlayer player = viewer.server.getPlayerList().getPlayer(entrant.id());
                if (player == null) {
                    // The frozen identity/slot is real, but an offline position or health value is unknown.
                    result.add(new Teammate(entrant.id(), entrant.name(), dimension, 0, 0, 0, 0, 0,
                            game.alive(entrant.id()), i + 1, COLORS[i % COLORS.length], false));
                    continue;
                }
                if (!MapOverlayPayloads.coordinate(player.getX()) || !MapOverlayPayloads.coordinate(player.getY())
                        || !MapOverlayPayloads.coordinate(player.getZ())) continue;
                float health = Float.isFinite(player.getHealth()) ? Math.clamp(player.getHealth(), 0, 1_000_000) : 0;
                float maximum = Float.isFinite(player.getMaxHealth()) ? Math.clamp(player.getMaxHealth(), 0, 1_000_000) : 0;
                result.add(new Teammate(entrant.id(), entrant.name(), player.serverLevel().dimension().location(),
                        player.getX(), player.getY(), player.getZ(), health, maximum, game.alive(entrant.id()),
                        i + 1, COLORS[i % COLORS.length], true));
            }
            return List.copyOf(result);
        }
        if (viewer.getTeam() == null || viewer.isSpectator()) return List.of();
        var dimension = viewer.serverLevel().dimension().location();
        // Include the viewer while allocating slot numbers, keeping teammates' labels consistent across clients.
        List<ServerPlayer> members = viewer.server.getPlayerList().getPlayers().stream()
                .filter(player -> player.getTeam() == viewer.getTeam() && !player.isSpectator())
                .sorted(Comparator.comparing(player -> player.getUUID().toString()))
                .limit(MapOverlayPayloads.MAX_TEAMMATES + 1L).toList();
        List<Teammate> result = new ArrayList<>();
        for (int i = 0; i < members.size() && result.size() < MapOverlayPayloads.MAX_TEAMMATES; i++) {
            ServerPlayer player = members.get(i);
            if (player == viewer || !dimension.equals(player.serverLevel().dimension().location())
                    || !MapOverlayPayloads.coordinate(player.getX()) || !MapOverlayPayloads.coordinate(player.getY())
                    || !MapOverlayPayloads.coordinate(player.getZ())) continue;
            float health = Float.isFinite(player.getHealth()) ? Math.clamp(player.getHealth(), 0, 1_000_000) : 0;
            float maximum = Float.isFinite(player.getMaxHealth()) ? Math.clamp(player.getMaxHealth(), 0, 1_000_000) : 0;
            result.add(new Teammate(player.getUUID(), player.getGameProfile().getName(), dimension,
                    player.getX(), player.getY(), player.getZ(), health, maximum, player.isAlive(), i + 1, COLORS[i % COLORS.length]));
        }
        return List.copyOf(result);
    }

    /** A publication caches geometry and its clock together, including forced login/datapack sends. */
    static final class SnapshotCache {
        final List<MapDefinition> maps;
        final List<MapDefinition> activeMaps;
        private final Map<Long, MapSnapshot> packets = new HashMap<>();

        SnapshotCache(List<MapDefinition> authored, List<MapDefinition> activeMaps,
                LongFunction<MatchStatus> statusLookup, ZoneSettings presentation) {
            this.activeMaps = List.copyOf(activeMaps);
            Map<Long, MapDefinition> active = new HashMap<>();
            for (var map : activeMaps) active.put(map.id(), map);
            maps = authored.stream().map(map -> active.getOrDefault(map.id(), map)).toList();
            for (var map : maps) {
                // A preparing/restoring map is locked by isRunning(), but has no active
                // session yet. Never pair its authored geometry with the ended match's clock.
                boolean running = active.containsKey(map.id());
                MatchStatus status = running ? statusLookup.apply(map.id()) : null;
                var target = status == null ? null : MatchCirclePreview.target(status, map.id());
                packets.put(map.id(), new MapSnapshot(0, map.dimension(), MapZoneModel.toJson(presentation.forDisplay(map)),
                        running, target, status == null ? -1 : status.flightProgress(), status));
            }
        }

        @Nullable MapSnapshot packet(long sequence, long mapId) {
            MapSnapshot snapshot = packets.get(mapId);
            return snapshot == null ? null : new MapSnapshot(sequence, snapshot.dimension(), snapshot.json(), snapshot.running(),
                    snapshot.nextCircle(), snapshot.flightProgress(), snapshot.matchStatus());
        }
    }

    private static final class State {
        long revision = -1, matchRevision = -1, settingsRevision = -2;
        SnapshotCache snapshots = new SnapshotCache(List.of(), List.of(), ignored -> null, ZoneSettings.defaults());
        final Map<UUID, Viewer> viewers = new HashMap<>();
    }
    private static final class Viewer {
        long revision = -1, matchRevision = -1, settingsRevision = -2, mapId = -1, mapSequence, teamSequence, catalogSequence;
        net.minecraft.resources.ResourceLocation dimension;
    }
}
