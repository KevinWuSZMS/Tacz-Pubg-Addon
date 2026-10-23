package com.example.examplemod.zones;

import com.example.examplemod.zones.MapOverlayPayloads.MapSnapshot;
import com.example.examplemod.zones.MapOverlayPayloads.TeamSnapshot;
import com.example.examplemod.zones.MapOverlayPayloads.Teammate;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchCirclePreview;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchSession;
import com.example.examplemod.zones.match.MatchStatus;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Read-only selection and bounded packet validation; no Minecraft client/server is created. */
public final class MapOverlayChecks {
    private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static int checks;
    public static void main(String[] args) {
        activeMatchSelection();
        cachedMatchLifecycle();
        var broad = map(1, 100);
        var small = map(2, 10);
        check(MapOverlayService.choose(List.of(broad, small), null, WORLD, 0, 0).id() == 2,
                "ordinary viewers get the smallest containing map without an editor selection");
        check(MapOverlayService.choose(List.of(broad, small), broad, WORLD, 0, 0).id() == 1,
                "explicit same-dimension editor choice takes priority");
        check(MapOverlayService.choose(List.of(broad), broad, NETHER, 0, 0) == null,
                "a retained editor selection never crosses dimensions visually");
        check(MapOverlayService.choose(List.of(small, map(3, 10)), null, WORLD, 0, 0).id() == 2,
                "equal-area maps choose the oldest ID consistently");
        check(MapOverlayService.choose(List.of(broad), null, WORLD, 101, 0) == null,
                "ordinary viewers outside every map do not receive an arbitrary map");
        check(MapOverlayService.choose(List.of(small), null, WORLD, 10, 10) != null,
                "inclusive map borders remain visible");
        check(MapOverlayService.choose(List.of(broad), null, WORLD, Double.NaN, 0) == null,
                "nonfinite viewer coordinates do not choose a map");
        var mapPacket = new MapSnapshot(1, WORLD, MapZoneModel.toJson(small));
        var buffer = buffer();
        try {
            MapSnapshot.CODEC.encode(buffer, mapPacket);
            check(MapSnapshot.CODEC.decode(buffer).equals(mapPacket), "map, flight path and sequence survive packet round trip");
            check(buffer.readableBytes() == 0, "map decoder consumes exactly one bounded packet");
        } finally { buffer.release(); }
        rejects(() -> new MapSnapshot(-1, WORLD, ""));
        rejects(() -> new MapSnapshot(1, NETHER, mapPacket.json()));
        rejects(() -> new MapSnapshot(1, WORLD, "x".repeat(MapZoneModel.MAX_JSON_BYTES + 1)));
        rejects(() -> new MapSnapshot(1, WORLD, "{}"));
        rejects(() -> new MapSnapshot(2, WORLD, "", true));
        Circle announced = new Circle(1, -1, 2);
        var openingMap = new MapDefinition(small.id(), small.name(), WORLD, small.minX(), small.minZ(),
                small.maxX(), small.maxZ(), null, 0, List.of(), small.flightPath(), small.visualStyle());
        var openingPacket = new MapSnapshot(3, WORLD, MapZoneModel.toJson(openingMap), true, announced, .25);
        buffer = buffer();
        try {
            MapSnapshot.CODEC.encode(buffer, openingPacket);
            var received = MapSnapshot.CODEC.decode(buffer);
            check(received.equals(openingPacket) && MapZoneModel.parse(received.json()).safeZone() == null,
                    "first white circle and plane progress survive without an active blue boundary");
        } finally { buffer.release(); }
        rejects(() -> new MapSnapshot(3, WORLD, mapPacket.json(), false, null, .25));
        rejects(() -> new MapSnapshot(3, WORLD, mapPacket.json(), true, null, Double.NaN));
        rejects(() -> new MapSnapshot(3, WORLD, mapPacket.json(), true, null, 1.1));
        var activePacket = new MapSnapshot(3, WORLD, MapZoneModel.toJson(small), true, announced);
        buffer = buffer();
        try {
            MapSnapshot.CODEC.encode(buffer, activePacket);
            var received = MapSnapshot.CODEC.decode(buffer);
            check(received.equals(activePacket) && received.running(), "running state is confirmed with its matching map");
            check(received.nextCircle().equals(announced) && MapZoneModel.parse(received.json()).safeZone().equals(small.safeZone()),
                    "blue boundary and fixed next safe circle arrive atomically without a HUD packet");
            check(buffer.readableBytes() == 0, "running map consumes the complete packet");
        } finally { buffer.release(); }
        for (var packet : List.of(new MapSnapshot(4, WORLD, activePacket.json(), true),
                new MapSnapshot(5, WORLD, activePacket.json()), new MapSnapshot(6, WORLD, ""))) {
            buffer = buffer();
            try {
                MapSnapshot.CODEC.encode(buffer, activePacket);
                MapSnapshot.CODEC.encode(buffer, packet);
                check(MapSnapshot.CODEC.decode(buffer).nextCircle().equals(announced), "announcement is retained with its map");
                check(MapSnapshot.CODEC.decode(buffer).nextCircle() == null && buffer.readableBytes() == 0,
                        "final phase, stopped match and cleared map explicitly remove the previous announcement");
            } finally { buffer.release(); }
        }
        rejects(() -> new MapSnapshot(7, WORLD, activePacket.json(), false, announced));
        rejects(() -> new MapSnapshot(7, WORLD, "", true, announced));
        for (var bad : List.of(new Circle(Double.NaN, 0, 2), new Circle(0, Double.POSITIVE_INFINITY, 2),
                new Circle(11, 0, 2), new Circle(0, 0, 0), new Circle(0, 0, Double.NaN),
                new Circle(0, 0, MapZoneModel.MAX_RADIUS + 1))) {
            rejects(() -> new MapSnapshot(7, WORLD, activePacket.json(), true, bad));
        }
        check(!new MapSnapshot(4, WORLD, "").running(), "absent map cannot leave damage-active HUD status behind");
        check(new MapSnapshot(2, WORLD, "").json().isEmpty(), "empty map packet explicitly clears the old map");
        var friend = new Teammate(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Friend", WORLD,
                1, 64, 2, 12, 20, true, 1, 0xFF5FD2FF);
        var offline = new Teammate(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Offline", WORLD,
                0, 0, 0, 0, 0, true, 2, 0xFFFFD84A, false);
        var eliminated = new Teammate(UUID.fromString("00000000-0000-0000-0000-000000000003"), "Eliminated", WORLD,
                1, 64, 2, 20, 20, false, 3, 0xFFFF79BE, true);
        var otherDimension = new Teammate(UUID.fromString("00000000-0000-0000-0000-000000000004"), "Elsewhere", NETHER,
                8, 64, 2, 12, 20, true, 4, 0xFF7FE280, true);
        var teamPacket = new TeamSnapshot(3, WORLD, List.of(friend, offline, eliminated, otherDimension));
        buffer = buffer();
        try {
            TeamSnapshot.CODEC.encode(buffer, teamPacket);
            check(TeamSnapshot.CODEC.decode(buffer).equals(teamPacket),
                    "online, offline, eliminated and cross-dimension roster status survives packet round trip");
            check(buffer.readableBytes() == 0, "team decoder consumes exactly one packet");
        } finally { buffer.release(); }
        check(friend.online() && friend.mapMarkerVisible(WORLD), "legacy teammate constructor remains an online map marker");
        check(!offline.mapMarkerVisible(WORLD), "offline roster placeholder cannot create a false map-origin marker");
        check(!eliminated.mapMarkerVisible(WORLD), "eliminated players cannot retain a living map marker after respawn");
        check(!otherDimension.mapMarkerVisible(WORLD) && otherDimension.mapMarkerVisible(NETHER),
                "cross-dimension roster identity remains available without a marker in the wrong dimension");
        rejects(() -> new TeamSnapshot(-1, WORLD, List.of()));
        rejects(() -> new TeamSnapshot(1, WORLD, List.of(friend, friend)));
        rejects(() -> new TeamSnapshot(1, WORLD, new ArrayList<>(java.util.Collections.nCopies(MapOverlayPayloads.MAX_TEAMMATES + 1, friend))));
        rejects(() -> new Teammate(friend.id(), "bad\nname", WORLD, 0, 0, 0, 20, 20, true, 1, 0));
        rejects(() -> new Teammate(friend.id(), "Friend", WORLD, Double.NaN, 0, 0, 20, 20, true, 1, 0));
        rejects(() -> new Teammate(friend.id(), "Friend", WORLD, 0, 0, 0, Float.NaN, 20, true, 1, 0));
        rejects(() -> new Teammate(friend.id(), "Friend", WORLD, 0, 0, 0, 20, -1, true, 1, 0));
        rejects(() -> new Teammate(friend.id(), "Friend", WORLD, 0, 0, 0, 20, 20, true, 0, 0));
        var invalidCount = buffer();
        try {
            invalidCount.writeVarLong(1); invalidCount.writeResourceLocation(WORLD);
            invalidCount.writeVarInt(MapOverlayPayloads.MAX_TEAMMATES + 1);
            rejects(() -> TeamSnapshot.CODEC.decode(invalidCount));
        } finally { invalidCount.release(); }
        for (int i = 0; i < 2; i++) {
            var part = new MapOverlayPayloads.CatalogPart(10, i, 2,
                    new MapSnapshot(10, WORLD, MapZoneModel.toJson(map(i + 1, 20 + i))));
            var catalogBuffer = buffer();
            try {
                MapOverlayPayloads.CatalogPart.CODEC.encode(catalogBuffer, part);
                check(MapOverlayPayloads.CatalogPart.CODEC.decode(catalogBuffer).equals(part), "each catalog map survives a bounded packet");
                check(catalogBuffer.readableBytes() == 0, "catalog consumes exactly one packet");
            } finally { catalogBuffer.release(); }
        }
        var emptyCatalog = new MapOverlayPayloads.CatalogPart(11, 0, 0, null);
        var emptyBuffer = buffer();
        try {
            MapOverlayPayloads.CatalogPart.CODEC.encode(emptyBuffer, emptyCatalog);
            check(MapOverlayPayloads.CatalogPart.CODEC.decode(emptyBuffer).equals(emptyCatalog), "empty catalog clears deleted maps");
        } finally { emptyBuffer.release(); }
        rejects(() -> new MapOverlayPayloads.CatalogPart(12, 2, 2, mapPacket));
        rejects(() -> new MapOverlayPayloads.CatalogPart(12, 0, MapZoneModel.MAX_MAPS + 1, mapPacket));
        System.out.println("Map overlay selection and packet checks passed: " + checks);
    }

    /** Reproduces a LAN restart with retained ended status, then forced sends between 2 Hz samples. */
    private static void cachedMatchLifecycle() {
        var authored = map(41, 100);
        var inactive = map(42, 20);
        var presentation = ZoneSettings.defaults();
        var first = overlayEngine(authored, 101);
        advance(first, 80);
        var firstStatus = first.status();
        check(firstStatus.stage().equals("shrinking"), "restart fixture reaches a real first-match contraction");
        var firstRuntime = first.projectedMap();
        int[] firstLookups = {0};
        var firstCache = new MapOverlayService.SnapshotCache(List.of(authored, inactive), List.of(firstRuntime), id -> {
            firstLookups[0]++;
            check(id == authored.id(), "inactive catalog maps never request runtime status");
            return first.status();
        }, presentation);
        check(firstLookups[0] == 1, "active map geometry and clock use one status read per cache construction");
        var firstPacket = firstCache.packet(100, authored.id());
        pairedSnapshot(firstPacket, firstRuntime, firstStatus, "first running match");
        roundTrip(firstPacket, "first contraction packet");

        for (int tick = 0; tick < 500 && !first.finished(); tick++) first.advance();
        check(first.finished() && first.status().stage().equals("finished"),
                "first contraction completes and the real engine retains its ended result");
        var ended = first.status();
        check(!firstStatus.currentCircle().equals(ended.currentCircle()),
                "ended first-match circle differs from its cached contraction and the authoring preview");
        int[] endedLookups = {0};
        var preparation = new MapOverlayService.SnapshotCache(List.of(authored, inactive), List.of(), id -> {
            endedLookups[0]++;
            return ended;
        }, presentation);
        check(endedLookups[0] == 0, "second-match preparation never reads the previous match's retained ended status");
        check(preparation.maps.equals(List.of(authored, inactive)) && preparation.activeMaps.isEmpty(),
                "preparation keeps authoring templates and an empty active-map sample");
        for (long sequence : new long[]{101, 102, 250}) {
            var packet = preparation.packet(sequence, authored.id());
            check(packet != null && packet.sequence() == sequence && !packet.running()
                    && packet.matchStatus() == null && packet.nextCircle() == null && packet.flightProgress() == -1,
                    "preparation packet clears first-match running state, clock, next circle and flight");
            check(MapZoneModel.parse(packet.json()).equals(authored),
                    "second-match preparation publishes its authoring template without the old final circle");
            roundTrip(packet, "preparation after an ended LAN match");
        }
        check(preparation.packet(251, 999) == null && endedLookups[0] == 0,
                "unknown maps return no packet and forced preparation packets never consult ended history");

        var second = overlayEngine(authored, 202);
        var secondOpeningStatus = second.status();
        var secondOpening = new MapOverlayService.SnapshotCache(List.of(authored, inactive),
                List.of(second.projectedMap()), id -> second.status(), presentation);
        var openingPacket = secondOpening.packet(252, authored.id());
        pairedSnapshot(openingPacket, second.projectedMap(), secondOpeningStatus, "second match opening");
        check(!openingPacket.matchStatus().sessionId().equals(ended.sessionId())
                && openingPacket.matchStatus().stage().equals("waiting")
                && MapZoneModel.parse(openingPacket.json()).safeZone() == null,
                "a new active session replaces ended history and begins without an inherited blue circle");
        roundTrip(openingPacket, "second match opening packet");

        advance(second, 80);
        var sampledStatus = second.status();
        var sampledRuntime = second.projectedMap();
        var mutableAuthored = new ArrayList<>(List.of(authored, inactive));
        var mutableActive = new ArrayList<>(List.of(sampledRuntime));
        int[] liveLookups = {0};
        var sampled = new MapOverlayService.SnapshotCache(mutableAuthored, mutableActive, id -> {
            liveLookups[0]++;
            check(id == authored.id(), "only active maps may sample live engine status");
            return second.status();
        }, presentation);
        mutableAuthored.clear(); mutableActive.clear();
        check(sampled.maps.size() == 2 && sampled.maps.contains(sampledRuntime)
                && sampled.activeMaps.equals(List.of(sampledRuntime)),
                "cache retains the sampled definitions independently of mutable caller lists");
        immutable(() -> sampled.maps.clear(), "cached catalog definition list is immutable");
        immutable(() -> sampled.activeMaps.clear(), "cached active definition list is immutable");

        advance(second, 9);
        check(second.status().matchTicks() == sampledStatus.matchTicks() + 9
                && !second.projectedMap().safeZone().equals(sampledRuntime.safeZone()),
                "engine moves nine real contraction ticks before the next 2 Hz publication");
        for (long sequence : new long[]{300, 301, 999}) {
            var current = sampled.packet(sequence, authored.id());
            pairedSnapshot(current, sampledRuntime, sampledStatus, "forced current-map send between cache samples");
            check(current.sequence() == sequence, "forced current-map send assigns its requested sequence");
            roundTrip(current, "forced cached current-map packet");
            for (int index = 0; index < sampled.maps.size(); index++) {
                var definition = sampled.maps.get(index);
                var catalogPacket = sampled.packet(sequence + 10, definition.id());
                if (definition.id() == authored.id()) {
                    pairedSnapshot(catalogPacket, sampledRuntime, sampledStatus, "catalog between cache samples");
                    check(current.json().equals(catalogPacket.json())
                            && current.matchStatus().equals(catalogPacket.matchStatus())
                            && current.nextCircle().equals(catalogPacket.nextCircle()),
                            "catalog and current-map sends share identical geometry and time across independent sequences");
                } else {
                    check(!catalogPacket.running() && catalogPacket.matchStatus() == null
                            && catalogPacket.nextCircle() == null && catalogPacket.flightProgress() == -1,
                            "inactive catalog entry stays independent of another map's active clock");
                }
                var part = new MapOverlayPayloads.CatalogPart(sequence + 10, index, sampled.maps.size(), catalogPacket);
                var buffer = buffer();
                try {
                    MapOverlayPayloads.CatalogPart.CODEC.encode(buffer, part);
                    check(MapOverlayPayloads.CatalogPart.CODEC.decode(buffer).equals(part) && !buffer.isReadable(),
                            "cached catalog round trip preserves one complete geometry-clock pair");
                } finally { buffer.release(); }
            }
        }
        check(liveLookups[0] == 1, "forced current-map and catalog packets never reread the advancing engine");
        var refreshedRuntime = second.projectedMap();
        var refreshedStatus = second.status();
        var refreshed = new MapOverlayService.SnapshotCache(List.of(authored, inactive), List.of(refreshedRuntime),
                id -> second.status(), presentation);
        var refreshedPacket = refreshed.packet(1_000, authored.id());
        pairedSnapshot(refreshedPacket, refreshedRuntime, refreshedStatus, "next 2 Hz cache sample");
        check(refreshedPacket.matchStatus().matchTicks() == sampledStatus.matchTicks() + 9
                && !refreshedPacket.json().equals(sampled.packet(1_001, authored.id()).json()),
                "rebuilding the cache advances geometry and clock together while older cache contents remain frozen");
        roundTrip(refreshedPacket, "refreshed geometry-clock packet");
    }

    private static MatchEngine overlayEngine(MapDefinition map, long seed) {
        var rules = new MatchConfigModel.Settings(map.id(), "overlay restart regression", 1, 20, 1,
                0, 20, 60, true, false, true, false,
                List.of(new MatchConfigModel.Phase(3, 7, .5, 1, true)), MatchConfigModel.BombingRules.defaults(),
                MatchConfigModel.FlowRules.defaults().withFirstCircleDelay(0), false, false,
                new MatchConfigModel.FlightRules(false, false, 40, 512, 3, 52));
        try {
            // The pure engine's constructor is package-visible to its runtime owner, not this overlay package.
            var constructor = MatchEngine.class.getDeclaredConstructor(MatchSession.class,
                    MatchConfigModel.Settings.class, List.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new MatchSession(new UUID(77, seed), 0, map), rules,
                    List.of(new MatchEngine.Participant(new UUID(0, 1), "overlay tester", "solo")), seed);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot create pure match-engine fixture", exception);
        }
    }

    private static void advance(MatchEngine engine, int ticks) {
        for (int tick = 0; tick < ticks; tick++) engine.advance();
    }

    private static void pairedSnapshot(MapSnapshot packet, MapDefinition runtime, MatchStatus status, String label) {
        check(packet != null && packet.running() && packet.matchStatus().equals(status),
                label + " retains the status sampled with its running definition");
        check(MapZoneModel.parse(packet.json()).equals(runtime)
                && java.util.Objects.equals(packet.nextCircle(), MatchCirclePreview.target(status, runtime.id()))
                && packet.flightProgress() == status.flightProgress(),
                label + " keeps geometry, next circle and flight paired with that status");
    }

    private static void roundTrip(MapSnapshot packet, String label) {
        var buffer = buffer();
        try {
            MapSnapshot.CODEC.encode(buffer, packet);
            check(MapSnapshot.CODEC.decode(buffer).equals(packet) && !buffer.isReadable(),
                    label + " survives a complete packet codec round trip");
        } finally { buffer.release(); }
    }

    private static void immutable(Runnable action, String message) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void activeMatchSelection() {
        var a = new MapDefinition(1, "Running A", WORLD, -100, -100, 100, 100,
                new Circle(0, 0, 5), 2, List.of());
        var bSafe = new MapDefinition(2, "Preview B", WORLD, -10, -10, 10, 10,
                new Circle(8, 0, 1), 3, List.of());
        var bBlue = new MapDefinition(2, "Running B", WORLD, -10, -10, 10, 10,
                new Circle(0, 0, 1), 3, List.of());
        var authored = List.of(a, bSafe);
        check(MapOverlayService.chooseRuntime(authored, List.of(), null, WORLD, 8, 0).id() == 2,
                "before any start the smallest preview map remains selected");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), null, WORLD, 8, 0).equals(a),
                "starting A reveals its damaging blue circle instead of smaller inactive B");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), bSafe, WORLD, 8, 0).equals(a),
                "explicit editor selection cannot hide a running blue-zone damage source");
        check(MapOverlayService.chooseRuntime(authored, List.of(bSafe, a), bSafe, WORLD, 8, 0).equals(a),
                "a second running map that is safe here cannot conceal A damage");
        check(MapOverlayService.chooseRuntime(authored, List.of(a, bBlue), a, WORLD, 8, 0).equals(bBlue),
                "strongest overlapping running blue-zone damage chooses its exact active snapshot");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), bBlue, WORLD, 8, 0).equals(a),
                "ending B immediately returns display choice to remaining active A");
        check(MapOverlayService.chooseRuntime(authored, List.of(), bSafe, WORLD, 8, 0).equals(bSafe),
                "ending all matches restores selected authoring preview instead of stale active state");
        check(MapOverlayService.chooseRuntime(authored, List.of(), null, WORLD, 8, 0).equals(bSafe),
                "ending all matches restores smallest-area preview for ordinary players");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), bSafe, WORLD, 0, 0).equals(a),
                "safe position still displays the containing running match before inactive previews");

        var equalDamage = new MapDefinition(3, "Equal damage", WORLD, -20, -20, 20, 20,
                new Circle(0, 0, 1), 2, List.of());
        for (var order : List.of(List.of(a, equalDamage), List.of(equalDamage, a))) {
            check(MapOverlayService.chooseRuntime(List.of(a, equalDamage), order, equalDamage, WORLD, 8, 0).equals(a),
                    "equal active damage resolves by map ID regardless of enumeration or editor selection");
            check(MapOverlayService.chooseRuntime(List.of(a, equalDamage), order, equalDamage, WORLD, 0, 0).equals(a),
                    "equally safe running maps resolve deterministically by map ID");
        }
        var otherDimension = new MapDefinition(4, "Nether", NETHER, -10, -10, 10, 10,
                new Circle(0, 0, 1), 100, List.of());
        check(MapOverlayService.chooseRuntime(authored, List.of(otherDimension), bSafe, WORLD, 8, 0).equals(bSafe),
                "active maps in another dimension do not hijack the viewer's preview");
        check(MapOverlayService.chooseRuntime(authored, List.of(bBlue), a, WORLD, 50, 0).equals(a),
                "a running map outside the viewer position does not hide a valid selected preview");
        check(MapOverlayService.chooseRuntime(authored, List.of(a, bBlue), bSafe, WORLD, 101, 0).equals(bSafe),
                "outside every running map the explicit same-dimension preview remains available");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), bSafe, WORLD, Double.NaN, 0) == null,
                "invalid player coordinates cannot choose an active or preview snapshot");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), bSafe, null, 8, 0) == null,
                "missing player dimension cannot choose an active or preview snapshot");
        check(MapOverlayService.chooseRuntime(authored, List.of(bBlue), a, WORLD, 10, 10).equals(bBlue),
                "inclusive active-map border stays consistent with the damage predicate");
        check(MapOverlayService.chooseRuntime(authored, List.of(a), bSafe, WORLD, 3, 4).equals(a),
                "safe-circle circumference remains safe and falls back to lowest-ID containing active map");
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
    }
    private static MapDefinition map(long id, double extent) {
        return new MapDefinition(id, "Map " + id, WORLD, -extent, -extent, extent, extent,
                new Circle(0, 0, extent / 2), 1, List.of(), new FlightPath(-extent, 0, extent, 0));
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid overlay input was accepted");
    }
}
