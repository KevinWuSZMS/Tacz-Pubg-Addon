package com.example.examplemod.zones.client;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.zones.MapOverlayPayloads;
import com.example.examplemod.zones.MapOverlayPayloads.Teammate;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/** Separate client snapshots; integrated-server state is never shared as mutable data. */
public final class MapOverlayClient {
    private static MapDefinition map;
    private static List<MapDefinition> maps = List.of();
    private static final java.util.Map<Long, MapOverlayPayloads.MapSnapshot> catalog = new java.util.LinkedHashMap<>();
    private static final java.util.Map<Long, MapOverlayPayloads.MapSnapshot> pending = new java.util.LinkedHashMap<>();
    private static long catalogRevision = -1;
    private static int nextPart;
    public static List<MapDefinition> allMaps() { return maps; }
    private static MapZoneModel.Circle nextCircle;
    private static List<Teammate> teammates = List.of();
    private static ResourceLocation dimension;
    private static long mapSequence = -1, teamSequence = -1, tick, teamsAt;
    private static boolean registered;
    private static boolean running;
    private record Clock(com.example.examplemod.zones.match.MatchStatus status, long receivedTick) {}
    private static final java.util.Map<Long, Clock> clocks = new java.util.HashMap<>();
    public record CircleFrame(com.example.examplemod.zones.match.MatchStatus status,
            MapZoneModel.Circle current, MapZoneModel.Circle target, double elapsedTicks,
            long remainingTicks, double animationTicks) {}
    private MapOverlayClient() {}

    public static void register() {
        if (registered) return;
        registered = true;
        MapOverlayPayloads.setCatalogHandler(packet -> {
            if (packet.revision() < catalogRevision) return;
            if (packet.index() == 0 && packet.revision() > catalogRevision) {
                catalogRevision = packet.revision(); nextPart = 0; pending.clear();
            }
            if (packet.revision() != catalogRevision || packet.index() != nextPart) return;
            if (packet.map() != null) {
                var definition = MapZoneModel.parse(packet.map().json());
                pending.put(definition.id(), packet.map());
                acceptClock(packet.map());
            }
            nextPart++;
            if (nextPart >= packet.count()) {
                catalog.clear(); catalog.putAll(pending);
                maps = catalog.values().stream().map(p -> MapZoneModel.parse(p.json())).toList();
            }
        });
        MapOverlayPayloads.setClientHandlers(packet -> {
            if (packet.sequence() <= mapSequence) return;
            try {
                MapDefinition next = packet.json().isEmpty() ? null : MapZoneModel.parse(packet.json());
                if (dimension != null && !dimension.equals(packet.dimension())) teammates = List.of();
                map = next; running = packet.running(); nextCircle = packet.nextCircle();
                acceptClock(packet);
                dimension = packet.dimension(); mapSequence = packet.sequence();
            } catch (IllegalArgumentException error) {
                ExampleMod.LOGGER.warn("Rejected invalid read-only map overlay", error);
            }
        }, packet -> {
            if (packet.sequence() <= teamSequence) return;
            if (dimension != null && !dimension.equals(packet.dimension())) { map = null; nextCircle = null; running = false; }
            dimension = packet.dimension(); teammates = packet.teammates();
            teamsAt = tick; teamSequence = packet.sequence();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && !mc.isPaused()) tick++;
            if (tick - teamsAt > 60) teammates = List.of();
            com.example.examplemod.zones.client.xaero.XaeroOverlayIntegration.tick();
        });
    }

    @Nullable public static MapDefinition current() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && map != null && mc.level.dimension().location().equals(map.dimension()) ? map : null;
    }

    public static List<Teammate> teammates() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension().location().equals(dimension) ? teammates : List.of();
    }

    public static boolean isMatchRunning() { return current() != null && running; }

    private static void acceptClock(MapOverlayPayloads.MapSnapshot packet) {
        if (packet.matchStatus() == null) {
            if (!packet.running() && !packet.json().isEmpty()) clocks.remove(MapZoneModel.parse(packet.json()).id());
            return;
        }
        var status = packet.matchStatus();
        var previous = clocks.get(status.mapId());
        if (previous == null || !previous.status().equals(status)) clocks.put(status.mapId(), new Clock(status, tick));
    }
    /** Map and its header share this one geometry/clock snapshot, including late joins and pauses. */
    @Nullable public static CircleFrame frameFor(MapDefinition displayed) {
        if (displayed == null || !runningFor(displayed)) return null;
        var clock = clocks.get(displayed.id());
        if (clock == null) return null;
        var status = clock.status();
        double partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        var frame = com.example.examplemod.zones.match.MatchCircleClock.sample(status, tick - clock.receivedTick() + partial);
        return new CircleFrame(status, frame.current(), frame.target(), frame.elapsedTicks(), frame.remainingTicks(), frame.animationTicks());
    }

    /** Retained until the next ordered map snapshot, including while single-player or the match is paused. */
    @Nullable public static MapZoneModel.Circle targetFor(MapDefinition displayed) {
        var snapshot = displayed == null ? null : catalog.get(displayed.id());
        return snapshot != null ? (snapshot.running() ? snapshot.nextCircle() : null) : runningFor(displayed) ? nextCircle : null;
    }

    public static boolean runningFor(MapDefinition displayed) {
        var snapshot = displayed == null ? null : catalog.get(displayed.id());
        if (snapshot != null) return snapshot.running() && snapshot.dimension().equals(displayed.dimension());
        var current = current();
        return running && current != null && displayed != null && displayed.id() == current.id()
                && displayed.dimension().equals(current.dimension());
    }
    public static double flightProgressFor(MapDefinition displayed) {
        var snapshot = displayed == null ? null : catalog.get(displayed.id());
        return snapshot != null && snapshot.running() && snapshot.dimension().equals(displayed.dimension())
                ? snapshot.flightProgress() : -1;
    }

    private static void clear() {
        com.example.examplemod.lootspawn.RoundLootService.clearClient();
        maps = List.of(); catalog.clear(); pending.clear(); catalogRevision = -1; nextPart = 0;
        map = null; nextCircle = null; running = false; teammates = List.of(); dimension = null;
        clocks.clear();
        mapSequence = -1; teamSequence = -1; tick = 0; teamsAt = 0;
    }
}
