package com.example.examplemod.zones;

import com.example.examplemod.zones.MapZoneModel.BombingZone;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinate;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Authoring geometry, optimistic edits, IDs and strict persistence checks in memory only. */
public final class MapZoneChecks {
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static int checks;

    private MapZoneChecks() {}

    public static void main(String[] args) {
        geometryAndClipping();
        validation();
        strictJson();
        flightPathsAndTwoDimensionalCoordinates();
        identityAndAtomicEdits();
        countLimits();
        persistence();
        malformedDataProtection();
        entityRecordingChoiceWire();
        entityRecordingChoiceGuards();
        System.out.println("Map zone checks passed: " + checks);
    }

    private static void entityRecordingChoiceGuards() {
        var close = bytecode("com/example/examplemod/zones/client/MapEntityRecordingScreen", "onClose", null);
        check(calls(close, "net/minecraft/client/Minecraft", "setScreen"), "ESC returns to the original editor screen");
        check(!calls(close, "java/util/function/Consumer", "accept") && !calls(close,
                "com/example/examplemod/zones/client/MapZoneConfigClient", "chooseEntityRecording"), "ESC never sends an inferred entity-recording answer");
        var choose = bytecode("com/example/examplemod/zones/MapZoneConfigPayloads", "chooseEntityRecording", "ServerPlayer");
        check(calls(choose, "com/example/examplemod/zones/MapZoneSavedData", "revision"), "Choice checks the map registry revision to reject deleted-and-reused IDs");
        check(calls(choose, "com/example/examplemod/zones/MapZoneSavedData", "map"), "Choice checks that the target map still exists");
        check(calls(choose, "com/example/examplemod/backup/MapResetService", "entityRecordingPending"), "Choice checks authoritative pending status before accepting an answer");
        var invoke = java.util.stream.StreamSupport.stream(choose.instructions.spliterator(), false)
                .filter(i -> i instanceof org.objectweb.asm.tree.MethodInsnNode).map(i -> (org.objectweb.asm.tree.MethodInsnNode)i).toList();
        int pending = -1, mutate = -1;
        for (int i=0;i<invoke.size();i++) { if (invoke.get(i).name.equals("entityRecordingPending")) pending=i;
            if (invoke.get(i).name.equals("chooseEntityRecording")) mutate=i; }
        check(pending>=0 && mutate>pending,"Authoritative pending check precedes the only entity-policy mutation");
        var guard = bytecode("com/example/examplemod/zones/MapZoneConfigPayloads", "lambda$onServer$", null);
        check(calls(guard,"net/minecraft/server/level/ServerPlayer","hasPermissions"), "Server permission gate surrounds entity choices and other map edits");
    }

    private static org.objectweb.asm.tree.MethodNode bytecode(String owner,String name,String descriptorPart) {
        try (var input=MapZoneChecks.class.getResourceAsStream("/"+owner+".class")) {
            var node=new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(input).accept(node,0);
            return node.methods.stream().filter(m -> (name.endsWith("$") ? m.name.startsWith(name) : m.name.equals(name))
                    && (descriptorPart==null || m.desc.contains(descriptorPart))).findFirst().orElseThrow();
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
    private static boolean calls(org.objectweb.asm.tree.MethodNode method,String owner,String name) {
        for(var insn:method.instructions) if(insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(owner)&&call.name.equals(name)) return true;
        return false;
    }

    private static void entityRecordingChoiceWire() {
        var request = java.util.UUID.randomUUID();
        var detail = new MapDefinition(7, "large map", OVERWORLD, 0, 0, 8192, 8192, null, 1, List.of());
        var snapshot = new MapZoneConfigPayloads.Snapshot(request, 28, 1, 0, 1, List.of(), 7, detail,
                MapZoneConfigPayloads.SAVED, "saved", "next", true);
        check(round(MapZoneConfigPayloads.Snapshot.CODEC, snapshot).equals(snapshot), "Pending entity choice remains attached to the exact saved map detail");
        for (boolean record : new boolean[]{false, true}) {
            var choice = new MapZoneConfigPayloads.EntityRecordingChoice(request, 28, 0, 1, 7, record);
            check(round(MapZoneConfigPayloads.EntityRecordingChoice.CODEC, choice).equals(choice), "Choice wire preserves request, revision, map identity and explicit answer");
        }
        rejects(() -> new MapZoneConfigPayloads.EntityRecordingChoice(request, -1, 0, 1, 7, true), "Choice cannot bypass revision ownership");
        rejects(() -> new MapZoneConfigPayloads.EntityRecordingChoice(request, 28, 0, 1, 0, true), "Choice requires a persisted map identity");
        rejects(() -> new MapZoneConfigPayloads.EntityRecordingChoice(request, 28, 0, 1, Long.MAX_VALUE, true), "Reserved map identity rejected");
        rejects(() -> new MapZoneConfigPayloads.EntityRecordingChoice(request, 28, 0, 17, 7, true), "Choice keeps bounded map page");
        rejects(() -> new MapZoneConfigPayloads.Snapshot(request, 28, 1, 0, 1, List.of(), 7, null,
                MapZoneConfigPayloads.PAGE, "saved", "next", true), "A pending flag without matching map detail is rejected");
        var decided = new MapZoneConfigPayloads.Snapshot(request, 28, 1, 0, 1, List.of(), 7, detail,
                MapZoneConfigPayloads.ENTITY_RECORDING_DECIDED, "decided", "next", false);
        check(round(MapZoneConfigPayloads.Snapshot.CODEC, decided).equals(decided), "Entity-only acknowledgement does not impersonate a map save");
    }

    private static <T> T round(net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,T> codec,T value) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.EMPTY, net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try { codec.encode(buffer,value); T decoded=codec.decode(buffer); check(buffer.readableBytes()==0,"Wire has no trailing fields"); return decoded; }
        finally { buffer.release(); }
    }

    private static void flightPathsAndTwoDimensionalCoordinates() {
        check(coordinates("12 -7").equals(new WorldCoordinates(new WorldCoordinate(false, 12),
                new WorldCoordinate(true, 0), new WorldCoordinate(false, -7))), "two integers stay exact X/Z without +0.5 or Y argument");
        check(coordinates("~1.25 ~-2").equals(new WorldCoordinates(new WorldCoordinate(true, 1.25),
                new WorldCoordinate(true, 0), new WorldCoordinate(true, -2))), "relative X/Z preserves two-dimensional semantics");
        rejects(() -> coordinates("^ ^"), "local coordinates cannot accidentally imply yaw-relative X/Z");
        rejects(() -> coordinates("1"), "a partial two-dimensional position is rejected");
        MapDefinition base = map(0, new Circle(0, 0, 5), List.of());
        check(base.flightPath() == null, "legacy constructor creates absent flight path");
        String legacyJson = MapZoneModel.toJson(base);
        check(!JsonParser.parseString(legacyJson).getAsJsonObject().has("flightPath")
                        && MapZoneModel.parse(legacyJson).flightPath() == null,
                "legacy JSON remains canonical and reads without flightPath");
        MapDefinition route = withFlight(base, new FlightPath(-10, 0, 10, 0));
        check(MapZoneModel.validate(route).isEmpty(), "directed flight path may begin and end on map boundary");
        check(MapZoneModel.parse(MapZoneModel.toJson(route)).equals(route), "flight direction and all four endpoints survive JSON round trip");
        invalid(withFlight(base, new FlightPath(0, 0, 0, 0)), "zero-length flight path rejected");
        invalid(withFlight(base, new FlightPath(-10.1, 0, 10, 0)), "flight start outside map rejected");
        invalid(withFlight(base, new FlightPath(-10, 0, 10, 10.1)), "flight end outside map rejected");
        invalid(withFlight(base, new FlightPath(Double.NaN, 0, 1, 1)), "nonfinite flight endpoint rejected");
        String json = MapZoneModel.toJson(route);
        rejects(() -> MapZoneModel.parse(json.replace("\"startX\":-10.0", "\"startX\":-10.0,\"altitude\":100")), "flight path unknown field rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"endX\":10.0", "\"endX\":\"10\"")), "flight endpoint numeric string rejected");
        MapZoneSavedData data = new MapZoneSavedData();
        MapDefinition saved = data.saveMap(0, route);
        check(saved.flightPath().equals(route.flightPath()) && data.revision() == 1, "assignment preserves flight path and notifies through revision");
        CompoundTag encoded = data.save(new CompoundTag(), null);
        check(encoded.getInt("version") == 1, "optional flight path does not invalidate existing storage version");
        MapZoneSavedData loaded = MapZoneSavedData.load(encoded, null);
        check(loaded.map(saved.id()).flightPath().equals(route.flightPath()), "flight path survives NBT persistence");
        MapDefinition removed = loaded.saveMap(loaded.revision(), withFlight(loaded.map(saved.id()), null));
        check(removed.flightPath() == null && loaded.revision() == 2, "flight removal is a normal revisioned atomic edit");
        CompoundTag before = loaded.save(new CompoundTag(), null);
        rejects(() -> loaded.saveMap(loaded.revision(), withFlight(removed, new FlightPath(99, 0, 1, 1))), "invalid route edit rejected");
        check(loaded.save(new CompoundTag(), null).equals(before), "invalid route edit does not mutate state or revision");
    }

    private static Coordinates coordinates(String input) {
        try {
            StringReader reader = new StringReader(input);
            Coordinates parsed = Vec2Argument.vec2(false).parse(reader);
            if (reader.canRead()) throw new IllegalArgumentException("Unexpected coordinates after X Z");
            return parsed;
        } catch (CommandSyntaxException error) { throw new IllegalArgumentException(error); }
    }

    private static MapDefinition withFlight(MapDefinition map, FlightPath path) {
        return new MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                map.safeZone(), map.blueDamagePerSecond(), map.bombingZones(), path);
    }

    private static void geometryAndClipping() {
        MapDefinition map = map(0, new Circle(0, 0, 5), List.of(bomb(8, new Circle(10, 0, 4))));
        check(map.area() == 400, "continuous map area does not add a block to either dimension");
        check(map.contains(-10, -10) && map.contains(10, 10), "rectangle corners are included");
        check(!map.contains(10.0001, 0) && !map.contains(0, -10.0001), "rectangle clips every effect");
        check(map.isSafe(OVERWORLD, 3, 4), "exact 3-4-5 circle boundary is safe");
        check(!map.isBlue(OVERWORLD, 3, 4), "exact circle boundary is never blue");
        check(map.isBlue(OVERWORLD, 3, 4.001), "just outside the circle is blue inside the map");
        check(!map.isBlue(OVERWORLD, 11, 0), "outside the map does not become blue");
        check(!map.isSafe(NETHER, 0, 0) && !map.isBlue(NETHER, 8, 0), "another dimension is not affected");
        check(map.isInBombingZone(8, OVERWORLD, 10, 4), "bombing circle boundary inside map is included");
        check(map.isInBombingZone(8, OVERWORLD, 6, 0), "bombing circle can intersect map boundary");
        check(!map.isInBombingZone(8, OVERWORLD, 11, 0), "bombing circle outside map is clipped");
        check(!map.isInBombingZone(9, OVERWORLD, 10, 0), "unrelated bombing ID does not match");
        check(!map.isInBombingZone(8, NETHER, 10, 0), "bombing zone is dimension-specific");
        check(!map.contains(Double.NaN, 0) && !map.isBlue(OVERWORLD, Double.POSITIVE_INFINITY, 0), "nonfinite queries never affect a location");
        check(!new Circle(0, 0, -2).contains(0, 0) && !new Circle(0, 0, Double.NaN).contains(0, 0), "invalid draft circles cannot match");
        MapDefinition noSafe = map(0, null, List.of());
        check(!noSafe.isBlue(OVERWORLD, 0, 0) && !noSafe.isSafe(OVERWORLD, 0, 0), "unset safe zone does not activate whole-map damage");
        MapDefinition covering = map(0, new Circle(0, 0, 100), List.of());
        check(MapZoneModel.validate(covering).isEmpty(), "safe circles larger than the rectangle are valid");
        check(covering.isSafe(OVERWORLD, 10, 10) && !covering.isSafe(OVERWORLD, 11, 0), "large safe circles are still clipped to map");
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                boolean inside = map.contains(x, z), safe = map.isSafe(OVERWORLD, x, z), blue = map.isBlue(OVERWORLD, x, z);
                check((safe || blue) == inside && !(safe && blue), "safe and blue partition the map without extending beyond it");
            }
        }
    }

    private static void validation() {
        MapDefinition base = map(0, null, List.of());
        check(MapZoneModel.validate(base).isEmpty(), "new map and absent safe zone are valid");
        check(MapZoneModel.validate(null).size() == 1, "missing map is explained");
        for (double bad : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                MapZoneModel.MAX_COORDINATE + 1, -MapZoneModel.MAX_COORDINATE - 1}) {
            invalid(new MapDefinition(0, "map", OVERWORLD, bad, -10, 10, 10, null, 1, List.of()), "invalid map coordinate rejected");
            invalid(map(0, new Circle(bad, 0, 1), List.of()), "invalid circle center rejected");
        }
        invalid(new MapDefinition(0, "map", OVERWORLD, 10, -10, 10, 10, null, 1, List.of()), "zero width rejected");
        invalid(new MapDefinition(0, "map", OVERWORLD, -10, 10, 10, -10, null, 1, List.of()), "reversed depth rejected instead of silently changing shape");
        for (String name : new String[]{"", "  ", "x".repeat(81), "bad\nname"}) {
            invalid(named(base, name), "invalid map name rejected");
        }
        check(MapZoneModel.validate(named(base, "地".repeat(80))).isEmpty(), "80-character Chinese name is preserved");
        invalid(new MapDefinition(0, "map", null, -10, -10, 10, 10, null, 1, List.of()), "missing dimension rejected");
        for (long id : new long[]{-1, Long.MAX_VALUE}) {
            invalid(map(id, null, List.of()), "invalid map ID rejected");
            invalid(map(0, null, List.of(bomb(id, new Circle(0, 0, 1)))), "invalid bombing ID rejected");
        }
        for (double radius : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, MapZoneModel.MAX_RADIUS + 1}) {
            invalid(map(0, new Circle(0, 0, radius), List.of()), "invalid safe radius rejected");
            invalid(map(0, null, List.of(bomb(0, new Circle(0, 0, radius)))), "invalid bombing radius rejected");
        }
        invalid(map(0, new Circle(10.1, 0, 1), List.of()), "safe center outside rectangle rejected");
        invalid(map(0, null, List.of(bomb(0, new Circle(0, -10.1, 1)))), "bomb center outside rectangle rejected");
        MapDefinition boundaryCenters = map(0, new Circle(10, 10, MapZoneModel.MAX_RADIUS),
                List.of(bomb(0, new Circle(-10, -10, 50))));
        check(MapZoneModel.validate(boundaryCenters).isEmpty(), "circle centers may lie on rectangle boundary and circles may cross it");
        invalid(map(0, null, List.of(bomb(2, new Circle(0, 0, 2)), bomb(2, new Circle(1, 1, 1)))), "positive bombing IDs must be unique");
        check(MapZoneModel.validate(map(0, null, List.of(bomb(0, new Circle(0, 0, 2)), bomb(0, new Circle(1, 1, 1))))).isEmpty(), "multiple new bombing drafts may use ID zero");
        for (double damage : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, MapZoneModel.MAX_DAMAGE + 1}) {
            invalid(new MapDefinition(0, "map", OVERWORLD, -10, -10, 10, 10, null, damage, List.of()), "invalid blue damage rejected");
            invalid(map(0, null, List.of(new BombingZone(0, new Circle(0, 0, 1), 60, damage, 4))), "invalid missile damage rejected");
        }
        for (int rate : new int[]{-1, 6001}) invalid(map(0, null, List.of(new BombingZone(0, new Circle(0, 0, 1), rate, 10, 4))), "invalid missile count rejected");
        for (double radius : new double[]{0, -1, 128.001, Double.NaN, Double.POSITIVE_INFINITY}) {
            invalid(map(0, null, List.of(new BombingZone(0, new Circle(0, 0, 1), 60, 10, radius))), "invalid blast radius rejected");
        }
        check(MapZoneModel.validate(map(0, null, List.of(new BombingZone(0, new Circle(0, 0, 1), 0, 0, 128)))).isEmpty(), "zero rate or zero damage is valid authoring data");
        check(MapZoneModel.DEFAULT_BLUE_DAMAGE_PER_SECOND == 1 && MapZoneModel.DEFAULT_MISSILES_PER_MINUTE == 60
                && MapZoneModel.DEFAULT_MISSILE_DAMAGE == 10 && MapZoneModel.DEFAULT_BLAST_RADIUS == 4, "editable starting defaults are explicit");
    }

    private static void strictJson() {
        MapDefinition value = map(0, new Circle(1, 2, 4), List.of(bomb(0, new Circle(10, 0, 20))));
        String json = MapZoneModel.toJson(value);
        check(MapZoneModel.parse(json).equals(value), "map JSON preserves every authoring field");
        check(json.contains("\"dimension\":\"minecraft:overworld\""), "dimension is serialized as an explicit string ID");
        JsonObject omittedSafe = JsonParser.parseString(MapZoneModel.toJson(map(0, null, List.of()))).getAsJsonObject();
        omittedSafe.remove("safeZone");
        check(MapZoneModel.parse(omittedSafe.toString()).safeZone() == null, "safe zone can be omitted in a new map draft");
        rejects(() -> MapZoneModel.parse(null), "missing JSON rejected");
        rejects(() -> MapZoneModel.parse(json + " {}"), "trailing document rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"id\":0", "\"id\":0,\"id\":1")), "duplicate object keys rejected");
        rejects(() -> MapZoneModel.parse(json.substring(0, json.length() - 1) + ",\"unknown\":1}"), "unknown root field rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"radius\":4.0", "\"radius\":4.0,\"height\":20")), "unknown circle fields rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"missileDamage\":10.0", "\"missileDamage\":10.0,\"enabled\":true")), "unknown bombing fields rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"blueDamagePerSecond\":1.0", "\"blueDamagePerSecond\":\"1\"")), "numeric strings rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"id\":0", "\"id\":0.5")), "fractional IDs rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"id\":0", "\"id\":9223372036854775808")), "overflowing IDs rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"missilesPerMinute\":60", "\"missilesPerMinute\":1.5")), "fractional missiles per minute rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"missilesPerMinute\":60", "\"missilesPerMinute\":2147483648")), "overflowing missile integer rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"radius\":4.0", "\"radius\":1e999")), "nonfinite numeric conversion rejected");
        rejects(() -> MapZoneModel.parse(json.replace("minecraft:overworld", "overworld")), "implicit dimension namespace rejected");
        rejects(() -> MapZoneModel.parse(json.replace("minecraft:overworld", "minecraft:")), "empty dimension path rejected");
        rejects(() -> MapZoneModel.parse(json.replace("\"name\":\"测试地图\"", "\"name\":null")), "null required name rejected");
        rejects(() -> MapZoneModel.parse("/* comment */" + json), "non-JSON comments rejected");
        rejects(() -> MapZoneModel.parse(" ".repeat(MapZoneModel.MAX_JSON_BYTES + 1)), "byte size is bounded before parsing");
        String multibyte = "地".repeat(MapZoneModel.MAX_JSON_BYTES / 2);
        rejects(() -> MapZoneModel.parse(multibyte), "size limit counts UTF-8 bytes rather than only Java characters");
        List<BombingZone> mutable = new ArrayList<>();
        MapDefinition frozen = map(0, null, mutable);
        mutable.add(bomb(0, new Circle(0, 0, 1)));
        check(frozen.bombingZones().isEmpty(), "caller mutation cannot alter stored list");
        rejects(() -> frozen.bombingZones().add(bomb(0, new Circle(0, 0, 1))), "published bombing list is immutable");
    }

    private static void identityAndAtomicEdits() {
        MapZoneSavedData data = new MapZoneSavedData();
        check(data.maps().isEmpty() && data.revision() == 0 && data.loadError().isEmpty(), "new data has no runtime or maps");
        check(data.map(1) == null, "missing map is nullable");
        MapDefinition first = data.saveMap(0, map(0, null, List.of(bomb(0, new Circle(0, 0, 2)))));
        check(first.id() == 1 && first.bombingZones().getFirst().id() == 1, "first map and bombing IDs are assigned independently");
        check(data.revision() == 1 && data.isDirty(), "successful edit marks saved data dirty and changes revision");
        CompoundTag before = data.save(new CompoundTag(), null);
        rejects(() -> data.saveMap(0, named(first, "stale")), "stale version rejected");
        check(data.save(new CompoundTag(), null).equals(before), "revision conflict does not change any data or counters");
        rejects(() -> data.saveMap(1, map(0, null, List.of(bomb(999, new Circle(0, 0, 1))))), "new map cannot claim a positive bombing ID");
        rejects(() -> data.saveMap(1, map(777, null, List.of())), "unknown positive map ID cannot be injected");
        rejects(() -> data.saveMap(1, named(first, "")), "invalid edited map rejected");
        check(data.save(new CompoundTag(), null).equals(before), "all validation failures leave state and next IDs unchanged");
        MapDefinition second = data.saveMap(1, inDimension(map(0, null, List.of(bomb(0, new Circle(1, 1, 1)))), NETHER));
        check(second.id() == 2 && second.bombingZones().getFirst().id() == 2, "rejected edits consume neither ID and sequence is save-wide");
        check(data.maps().stream().map(MapDefinition::id).toList().equals(List.of(1L, 2L)), "maps are returned in ID order");
        rejects(() -> data.maps().clear(), "published map list is immutable");
        MapDefinition stolen = map(first.id(), null, second.bombingZones());
        CompoundTag twoMaps = data.save(new CompoundTag(), null);
        rejects(() -> data.saveMap(2, stolen), "map cannot reuse another map's bombing ID");
        check(data.save(new CompoundTag(), null).equals(twoMaps), "foreign ownership failure is atomic");
        MapDefinition edited = data.saveMap(2, named(first, "重新命名"));
        check(edited.id() == 1 && edited.bombingZones().getFirst().id() == 1 && edited.name().equals("重新命名"), "editing preserves own map and bombing IDs");
        data.saveMap(3, map(first.id(), null, List.of()));
        MapDefinition withNewBomb = data.saveMap(4, map(first.id(), null, List.of(bomb(0, new Circle(0, 0, 1)))));
        check(withNewBomb.bombingZones().getFirst().id() == 3, "removed bombing IDs are never reused");
        rejects(() -> data.saveMap(5, first), "retired bombing ID cannot be resurrected by edit");
        data.deleteMap(5, second.id());
        check(data.map(second.id()) == null && data.revision() == 6, "map deletion removes only requested map");
        MapDefinition third = data.saveMap(6, map(0, null, List.of(bomb(0, new Circle(0, 0, 1)))));
        check(third.id() == 2 && third.bombingZones().getFirst().id() == 4, "new maps follow the largest surviving map ID; bombing IDs stay unique");
        CompoundTag after = data.save(new CompoundTag(), null);
        rejects(() -> data.deleteMap(0, first.id()), "delete also checks revision");
        rejects(() -> data.deleteMap(7, 999), "delete rejects unknown map");
        check(data.save(new CompoundTag(), null).equals(after), "failed deletes leave all state unchanged");
    }

    private static void countLimits() {
        MapZoneSavedData data = new MapZoneSavedData();
        for (int i = 0; i < MapZoneModel.MAX_MAPS; i++) data.saveMap(data.revision(), map(0, null, List.of()));
        CompoundTag before = data.save(new CompoundTag(), null);
        rejects(() -> data.saveMap(data.revision(), map(0, null, List.of())), "save-wide map cap enforced");
        check(data.maps().size() == 256 && data.save(new CompoundTag(), null).equals(before), "over-limit creation is atomic");
        data.deleteMap(data.revision(), 128);
        check(data.saveMap(data.revision(), map(0, null, List.of())).id() == 257, "deleting a lower ID does not renumber surviving maps");
        List<BombingZone> zones = new ArrayList<>();
        for (int i = 0; i < MapZoneModel.MAX_BOMBING_ZONES; i++) zones.add(bomb(0, new Circle(0, 0, 1)));
        MapZoneSavedData bombs = new MapZoneSavedData();
        MapDefinition maximum = bombs.saveMap(0, map(0, null, zones));
        check(maximum.bombingZones().size() == 32 && maximum.bombingZones().getLast().id() == 32, "maximum bombing count receives distinct IDs");
        zones.add(bomb(0, new Circle(0, 0, 1)));
        rejects(() -> bombs.saveMap(1, map(maximum.id(), null, zones)), "over-limit bombing count rejected before assignment");
        check(bombs.revision() == 1 && bombs.map(1).equals(maximum), "over-limit bombing edit preserves original map");
    }

    private static void persistence() {
        MapZoneSavedData data = new MapZoneSavedData();
        data.saveMap(0, map(0, new Circle(1, 1, 50), List.of(bomb(0, new Circle(10, 0, 40)))));
        MapDefinition removed = data.saveMap(1, inDimension(map(0, null, List.of(bomb(0, new Circle(0, 0, 2)))), NETHER));
        data.deleteMap(2, removed.id());
        CompoundTag saved = data.save(new CompoundTag(), null);
        MapZoneSavedData loaded = MapZoneSavedData.load(saved, null);
        check(loaded.loadError().isEmpty() && !loaded.isDirty(), "ordinary in-memory load has no errors or writeback");
        check(loaded.revision() == data.revision() && loaded.maps().equals(data.maps()), "NBT restores all geometry, dimensions, settings and version");
        check(loaded.save(new CompoundTag(), null).equals(saved), "NBT round trip preserves exact canonical representation");
        MapDefinition next = loaded.saveMap(loaded.revision(), map(0, null, List.of(bomb(0, new Circle(0, 0, 1)))));
        check(next.id() == 2 && next.bombingZones().getFirst().id() == 3, "map allocation follows surviving IDs after reload; bombing allocation stays monotonic");
        check(MapZoneSavedData.load(new CompoundTag(), null).maps().isEmpty(), "empty initialization is supported");
        CompoundTag exhaustedIds = saved.copy();
        exhaustedIds.putLong("next_map_id", Long.MAX_VALUE);
        ListTag exhaustedMaps = new ListTag();
        exhaustedMaps.add(StringTag.valueOf(MapZoneModel.toJson(map(Long.MAX_VALUE - 1, null, List.of()))));
        exhaustedIds.put("maps", exhaustedMaps);
        MapZoneSavedData exhausted = MapZoneSavedData.load(exhaustedIds, null);
        check(exhausted.loadError().isEmpty(), "exhausted counter is a valid read-only allocation boundary");
        rejects(() -> exhausted.saveMap(exhausted.revision(), map(0, null, List.of())), "exhausted counter cannot wrap to a reused ID");
        check(exhausted.save(new CompoundTag(), null).equals(exhaustedIds), "allocation overflow leaves original state untouched");
        CompoundTag lastIds = saved.copy();
        lastIds.putLong("next_map_id", Long.MAX_VALUE - 1);
        ListTag lastMaps = new ListTag();
        lastMaps.add(StringTag.valueOf(MapZoneModel.toJson(map(Long.MAX_VALUE - 2, null, List.of()))));
        lastIds.put("maps", lastMaps);
        MapZoneSavedData last = MapZoneSavedData.load(lastIds, null);
        check(last.saveMap(last.revision(), map(0, null, List.of())).id() == Long.MAX_VALUE - 1, "last valid map ID can be assigned once");
        rejects(() -> last.saveMap(last.revision(), map(0, null, List.of())), "last allocation cannot wrap");
        CompoundTag exhaustedRevision = saved.copy();
        exhaustedRevision.putLong("revision", Long.MAX_VALUE);
        MapZoneSavedData noRevision = MapZoneSavedData.load(exhaustedRevision, null);
        rejects(() -> noRevision.deleteMap(Long.MAX_VALUE, 1), "revision overflow cannot occur on delete");
        rejects(() -> noRevision.saveMap(Long.MAX_VALUE, noRevision.map(1)), "revision overflow cannot occur on edit");
    }

    private static void malformedDataProtection() {
        MapZoneSavedData source = new MapZoneSavedData();
        source.saveMap(0, map(0, null, List.of(bomb(0, new Circle(0, 0, 1)))));
        source.saveMap(1, map(0, null, List.of(bomb(0, new Circle(0, 0, 1)))));
        CompoundTag valid = source.save(new CompoundTag(), null);
        CompoundTag future = valid.copy(); future.putInt("version", 2); protectedFailure(future, "unsupported future version");
        CompoundTag missing = valid.copy(); missing.remove("revision"); protectedFailure(missing, "missing revision");
        CompoundTag badType = valid.copy(); badType.putInt("revision", 2); protectedFailure(badType, "wrong counter type");
        CompoundTag negative = valid.copy(); negative.putLong("revision", -1); protectedFailure(negative, "negative revision");
        CompoundTag lagging = valid.copy(); lagging.putLong("next_map_id", 1); protectedFailure(lagging, "lagging map ID counter");
        CompoundTag laggingBomb = valid.copy(); laggingBomb.putLong("next_bombing_zone_id", 2); protectedFailure(laggingBomb, "lagging bombing ID counter");
        CompoundTag wrongList = valid.copy(); wrongList.putString("maps", "not a list"); protectedFailure(wrongList, "wrong maps type");
        CompoundTag unknown = valid.copy(); unknown.putString("future_data", "preserve me"); protectedFailure(unknown, "unknown data field");
        CompoundTag corrupt = valid.copy();
        ListTag corruptMaps = new ListTag(); corruptMaps.add(StringTag.valueOf("{broken")); corrupt.put("maps", corruptMaps);
        protectedFailure(corrupt, "malformed map JSON");
        CompoundTag duplicateMap = valid.copy();
        ListTag duplicates = new ListTag();
        duplicates.add(StringTag.valueOf(MapZoneModel.toJson(source.map(1))));
        duplicates.add(StringTag.valueOf(MapZoneModel.toJson(source.map(1))));
        duplicateMap.put("maps", duplicates); protectedFailure(duplicateMap, "duplicate map ID");
        CompoundTag duplicateBomb = valid.copy();
        ListTag duplicateBombMaps = new ListTag();
        duplicateBombMaps.add(StringTag.valueOf(MapZoneModel.toJson(source.map(1))));
        duplicateBombMaps.add(StringTag.valueOf(MapZoneModel.toJson(map(2, null, source.map(1).bombingZones()))));
        duplicateBomb.put("maps", duplicateBombMaps); protectedFailure(duplicateBomb, "cross-map duplicate bombing ID");
        CompoundTag unsaved = valid.copy();
        ListTag unsavedMaps = new ListTag(); unsavedMaps.add(StringTag.valueOf(MapZoneModel.toJson(map(0, null, List.of()))));
        unsaved.put("maps", unsavedMaps); protectedFailure(unsaved, "persisted draft ID");
        CompoundTag tooMany = valid.copy();
        ListTag tooManyMaps = new ListTag();
        for (int i = 0; i <= MapZoneModel.MAX_MAPS; i++) tooManyMaps.add(StringTag.valueOf(MapZoneModel.toJson(map(i + 1, null, List.of()))));
        tooMany.put("maps", tooManyMaps); protectedFailure(tooMany, "too many saved maps");
    }

    private static void protectedFailure(CompoundTag malformed, String label) {
        CompoundTag original = malformed.copy();
        MapZoneSavedData data = MapZoneSavedData.load(malformed, null);
        check(!data.loadError().isEmpty() && data.maps().isEmpty() && !data.isDirty(), label + " freezes editing without scheduling destructive writeback");
        rejects(() -> data.saveMap(data.revision(), map(0, null, List.of())), label + " rejects creation");
        rejects(() -> data.deleteMap(data.revision(), 1), label + " rejects deletion");
        malformed.putString("external_mutation", "must not leak into protected data");
        data.setDirty();
        check(data.save(new CompoundTag(), null).equals(original), label + " preserves original NBT even after external dirty flag");
    }

    private static MapDefinition map(long id, Circle safe, List<BombingZone> zones) {
        return new MapDefinition(id, "测试地图", OVERWORLD, -10, -10, 10, 10, safe, 1, zones);
    }

    private static BombingZone bomb(long id, Circle circle) { return new BombingZone(id, circle, 60, 10, 4); }

    private static MapDefinition named(MapDefinition map, String name) {
        return new MapDefinition(map.id(), name, map.dimension(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                map.safeZone(), map.blueDamagePerSecond(), map.bombingZones());
    }

    private static MapDefinition inDimension(MapDefinition map, ResourceLocation dimension) {
        return new MapDefinition(map.id(), map.name(), dimension, map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                map.safeZone(), map.blueDamagePerSecond(), map.bombingZones());
    }

    private static void invalid(MapDefinition map, String message) {
        check(!MapZoneModel.validate(map).isEmpty(), message);
        rejects(() -> MapZoneModel.toJson(map), message + " during serialization");
    }

    private static void rejects(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); }
        catch (RuntimeException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
