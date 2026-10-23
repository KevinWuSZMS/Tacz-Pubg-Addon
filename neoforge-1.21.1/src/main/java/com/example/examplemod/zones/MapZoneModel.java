package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable authoring definitions. Nothing in this model starts a match or applies damage. */
public final class MapZoneModel {
    public static final int MAX_MAPS = 256;
    public static final int MAX_BOMBING_ZONES = 32;
    public static final int MAX_JSON_BYTES = 24 * 1024;
    public static final double MAX_COORDINATE = 29_999_984;
    public static final double MAX_RADIUS = 60_000_000;
    public static final double MAX_DAMAGE = 10_000;
    public static final int MAX_MISSILES_PER_MINUTE = 6_000;
    public static final double MAX_BLAST_RADIUS = 128;
    public static final double DEFAULT_BLUE_DAMAGE_PER_SECOND = 1;
    public static final int DEFAULT_MISSILES_PER_MINUTE = 60;
    public static final double DEFAULT_MISSILE_DAMAGE = 10;
    public static final double DEFAULT_BLAST_RADIUS = 4;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MapZoneModel() {}

    public record Circle(double centerX, double centerZ, double radius) {
        /** Boundary points belong to the circle. Y is deliberately absent. */
        public boolean contains(double x, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(centerX)
                    || !Double.isFinite(centerZ) || !Double.isFinite(radius) || radius <= 0) return false;
            double dx = x - centerX, dz = z - centerZ;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    public record BombingZone(long id, Circle area, int missilesPerMinute,
            double missileDamage, double blastRadius) {}

    /** Directed X/Z authoring line only; no aircraft or automatic match activity. */
    public record FlightPath(double startX, double startZ, double endX, double endZ) {}

    public record MapDefinition(long id, String name, ResourceLocation dimension,
            double minX, double minZ, double maxX, double maxZ, @Nullable Circle safeZone,
            double blueDamagePerSecond, List<BombingZone> bombingZones, @Nullable FlightPath flightPath,
            MapVisualStyle visualStyle, @Nullable Circle stagingSpawn) {
        public MapDefinition {
            bombingZones = List.copyOf(Objects.requireNonNull(bombingZones, "bombingZones"));
            Objects.requireNonNull(visualStyle, "visualStyle");
        }

        /** Existing maps have no preparation spawn until the author explicitly sets one. */
        public MapDefinition(long id, String name, ResourceLocation dimension,
                double minX, double minZ, double maxX, double maxZ, @Nullable Circle safeZone,
                double blueDamagePerSecond, List<BombingZone> bombingZones, @Nullable FlightPath flightPath,
                MapVisualStyle visualStyle) {
            this(id, name, dimension, minX, minZ, maxX, maxZ, safeZone, blueDamagePerSecond,
                    bombingZones, flightPath, visualStyle, null);
        }

        /** Legacy authors keep the shared default palette until explicitly edited. */
        public MapDefinition(long id, String name, ResourceLocation dimension,
                double minX, double minZ, double maxX, double maxZ, @Nullable Circle safeZone,
                double blueDamagePerSecond, List<BombingZone> bombingZones, @Nullable FlightPath flightPath) {
            this(id, name, dimension, minX, minZ, maxX, maxZ, safeZone,
                    blueDamagePerSecond, bombingZones, flightPath, MapVisualStyle.DEFAULT);
        }

        /** Old authors and saved definitions have no flight path until one is explicitly added. */
        public MapDefinition(long id, String name, ResourceLocation dimension,
                double minX, double minZ, double maxX, double maxZ, @Nullable Circle safeZone,
                double blueDamagePerSecond, List<BombingZone> bombingZones) {
            this(id, name, dimension, minX, minZ, maxX, maxZ, safeZone, blueDamagePerSecond, bombingZones, null, MapVisualStyle.DEFAULT);
        }

        /** Continuous inclusive coordinate boundaries; no block-selection +1 expansion. */
        public boolean contains(double x, double z) {
            return Double.isFinite(x) && Double.isFinite(z)
                    && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean isSafe(ResourceLocation dimension, double x, double z) {
            return this.dimension != null && this.dimension.equals(dimension)
                    && contains(x, z) && safeZone != null && safeZone.contains(x, z);
        }

        public boolean isBlue(ResourceLocation dimension, double x, double z) {
            return this.dimension != null && this.dimension.equals(dimension)
                    && contains(x, z) && safeZone != null && !safeZone.contains(x, z);
        }

        public boolean isInBombingZone(long zoneId, ResourceLocation dimension, double x, double z) {
            if (this.dimension == null || !this.dimension.equals(dimension) || !contains(x, z)) return false;
            return bombingZones.stream().anyMatch(zone -> zone.id == zoneId && zone.area != null && zone.area.contains(x, z));
        }

        public double area() { return (maxX - minX) * (maxZ - minZ); }
    }

    /** ID zero is reserved for unsaved map/zone drafts; positive IDs belong to SavedData. */
    public static List<String> validate(MapDefinition map) {
        if (map == null) return List.of(Texts.tr("message.tacz_pubg.map_zone_model.select_map_configuration_first"));
        List<String> errors = new ArrayList<>();
        id(errors, Texts.tr("message.tacz_pubg.map_zone_model.map_id"), map.id);
        if (map.name == null || map.name.isBlank() || map.name.length() > 80
                || map.name.codePoints().anyMatch(Character::isISOControl)) {
            errors.add(Texts.tr("message.tacz_pubg.map_zone_model.enter_displayable_characters_as_map_name"));
        }
        if (map.dimension == null) errors.add(Texts.tr("message.tacz_pubg.map_zone_model.enter_map_dimension_id"));
        else if (map.dimension.toString().length() > 128) errors.add(Texts.tr("message.tacz_pubg.map_zone_model.map_dimension_id_not_exceed_characters"));
        coordinate(errors, Texts.tr("message.tacz_pubg.map_zone_model.map_minimum_x"), map.minX);
        coordinate(errors, Texts.tr("message.tacz_pubg.map_zone_model.map_minimum_z"), map.minZ);
        coordinate(errors, Texts.tr("message.tacz_pubg.map_zone_model.map_maximum_x"), map.maxX);
        coordinate(errors, Texts.tr("message.tacz_pubg.map_zone_model.map_maximum_z"), map.maxZ);
        if (!(map.maxX > map.minX) || !(map.maxZ > map.minZ)) errors.add(Texts.tr("message.tacz_pubg.map_zone_model.rectangular_map_width_depth_positive"));
        range(errors, Texts.tr("screen.tacz_pubg.map_config.blue_zone_damage_per_second_hp"), map.blueDamagePerSecond, 0, MAX_DAMAGE);
        if (map.safeZone != null) circle(errors, Texts.tr("screen.tacz_pubg.map_overlay_hud.safe_zone"), map.safeZone, map);
        if (map.stagingSpawn != null) {
            Circle spawn = map.stagingSpawn;
            if (!Double.isFinite(spawn.centerX()) || !Double.isFinite(spawn.centerZ()) || !Double.isFinite(spawn.radius())
                    || spawn.radius() <= 0 || spawn.radius() > MAX_RADIUS
                    || !map.contains(spawn.centerX() - spawn.radius(), spawn.centerZ() - spawn.radius())
                    || !map.contains(spawn.centerX() + spawn.radius(), spawn.centerZ() + spawn.radius()))
                errors.add(Texts.tr("screen.tacz_pubg.map_staging.invalid"));
        }
        if (map.flightPath != null) {
            FlightPath path = map.flightPath;
            coordinate(errors, Texts.tr("screen.tacz_pubg.map_config.route_start_x"), path.startX);
            coordinate(errors, Texts.tr("screen.tacz_pubg.map_config.route_start_z"), path.startZ);
            coordinate(errors, Texts.tr("screen.tacz_pubg.map_config.route_end_x"), path.endX);
            coordinate(errors, Texts.tr("screen.tacz_pubg.map_config.route_end_z"), path.endZ);
            if (!map.contains(path.startX, path.startZ) || !map.contains(path.endX, path.endZ)) {
                errors.add(Texts.tr("message.tacz_pubg.map_zone_model.both_flight_route_endpoints_inside_map_rectangle"));
            }
            if (path.startX == path.endX && path.startZ == path.endZ) errors.add(Texts.tr("message.tacz_pubg.map_zone_model.choose_two_distinct_flight_route_endpoints"));
        }
        if (map.bombingZones.size() > MAX_BOMBING_ZONES) errors.add(Texts.tr("message.tacz_pubg.map_zone_model.each_map_allows_most_bombardment_zones", MAX_BOMBING_ZONES));
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < map.bombingZones.size(); i++) {
            BombingZone zone = map.bombingZones.get(i);
            String label = Texts.tr("message.tacz_pubg.map_zone_model.bombardment_zone", (i + 1));
            id(errors, label + Texts.tr("message.tacz_pubg.map_zone_model.id"), zone.id);
            if (zone.id > 0 && !ids.add(zone.id)) errors.add(Texts.tr("message.tacz_pubg.map_zone_model.duplicate_bombardment_zone_id", zone.id));
            circle(errors, label, zone.area, map);
            if (zone.missilesPerMinute < 0 || zone.missilesPerMinute > MAX_MISSILES_PER_MINUTE) {
                errors.add(label + Texts.tr("message.tacz_pubg.map_zone_model.missiles_per_minute") + MAX_MISSILES_PER_MINUTE + Texts.tr("message.tacz_pubg.map_zone_model.integer"));
            }
            range(errors, label + Texts.tr("message.tacz_pubg.map_zone_model.missile_damage_hp"), zone.missileDamage, 0, MAX_DAMAGE);
            if (!Double.isFinite(zone.blastRadius) || zone.blastRadius <= 0 || zone.blastRadius > MAX_BLAST_RADIUS) {
                errors.add(label + Texts.tr("message.tacz_pubg.map_zone_model.valid_explosion_radius") + MAX_BLAST_RADIUS + "]");
            }
        }
        return List.copyOf(errors);
    }

    public static String toJson(MapDefinition map) {
        requireValid(map);
        JsonObject root = new JsonObject();
        root.addProperty("id", map.id);
        root.addProperty("name", map.name);
        root.addProperty("dimension", map.dimension.toString());
        root.addProperty("minX", map.minX); root.addProperty("minZ", map.minZ);
        root.addProperty("maxX", map.maxX); root.addProperty("maxZ", map.maxZ);
        root.add("safeZone", map.safeZone == null ? JsonNull.INSTANCE : circleJson(map.safeZone));
        if (map.stagingSpawn != null) root.add("stagingSpawn", circleJson(map.stagingSpawn));
        root.addProperty("blueDamagePerSecond", map.blueDamagePerSecond);
        root.add("visualStyle", styleJson(map.visualStyle));
        if (map.flightPath != null) {
            JsonObject path = new JsonObject();
            path.addProperty("startX", map.flightPath.startX); path.addProperty("startZ", map.flightPath.startZ);
            path.addProperty("endX", map.flightPath.endX); path.addProperty("endZ", map.flightPath.endZ);
            root.add("flightPath", path);
        }
        JsonArray zones = new JsonArray();
        for (BombingZone zone : map.bombingZones) {
            JsonObject value = new JsonObject();
            value.addProperty("id", zone.id); value.add("area", circleJson(zone.area));
            value.addProperty("missilesPerMinute", zone.missilesPerMinute);
            value.addProperty("missileDamage", zone.missileDamage); value.addProperty("blastRadius", zone.blastRadius);
            zones.add(value);
        }
        root.add("bombingZones", zones);
        String json = GSON.toJson(root);
        checkSize(json);
        return json;
    }

    public static MapDefinition parse(String json) {
        try {
            JsonObject root = object(readJson(json), Texts.tr("screen.tacz_pubg.map_config.map"));
            keys(root, Set.of("id", "name", "dimension", "minX", "minZ", "maxX", "maxZ",
                    "safeZone", "blueDamagePerSecond", "bombingZones", "flightPath", "visualStyle", "stagingSpawn"), Texts.tr("screen.tacz_pubg.map_config.map"));
            String dimension = string(root, "dimension");
            int separator = dimension.indexOf(':');
            if (dimension.length() > 128 || separator <= 0 || separator == dimension.length() - 1) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.map_dimension_full_namespace_path_id"));
            }
            ResourceLocation dimensionId = ResourceLocation.tryParse(dimension);
            if (dimensionId == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.invalid_map_dimension_id_format"));
            Circle safe = root.has("safeZone") && !root.get("safeZone").isJsonNull()
                    ? parseCircle(root.get("safeZone"), Texts.tr("screen.tacz_pubg.map_overlay_hud.safe_zone")) : null;
            FlightPath flight = null;
            if (root.has("flightPath") && !root.get("flightPath").isJsonNull()) {
                JsonObject path = object(root.get("flightPath"), Texts.tr("screen.tacz_pubg.zone_settings.flight_route"));
                keys(path, Set.of("startX", "startZ", "endX", "endZ"), Texts.tr("screen.tacz_pubg.zone_settings.flight_route"));
                flight = new FlightPath(number(path, "startX"), number(path, "startZ"),
                        number(path, "endX"), number(path, "endZ"));
            }
            JsonElement zoneElement = required(root, "bombingZones");
            if (!zoneElement.isJsonArray() || zoneElement.getAsJsonArray().size() > MAX_BOMBING_ZONES) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.bombardment_zones_array_most_entries", MAX_BOMBING_ZONES));
            }
            List<BombingZone> zones = new ArrayList<>();
            for (JsonElement element : zoneElement.getAsJsonArray()) {
                JsonObject zone = object(element, Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone"));
                keys(zone, Set.of("id", "area", "missilesPerMinute", "missileDamage", "blastRadius"), Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone"));
                zones.add(new BombingZone(integer(zone, "id"), parseCircle(required(zone, "area"), Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone")),
                        intNumber(zone, "missilesPerMinute"), number(zone, "missileDamage"), number(zone, "blastRadius")));
            }
            MapDefinition map = new MapDefinition(integer(root, "id"), string(root, "name"), dimensionId,
                    number(root, "minX"), number(root, "minZ"), number(root, "maxX"), number(root, "maxZ"),
                    safe, number(root, "blueDamagePerSecond"), zones, flight,
                    root.has("visualStyle") ? parseStyle(root.get("visualStyle")) : MapVisualStyle.DEFAULT,
                    root.has("stagingSpawn") && !root.get("stagingSpawn").isJsonNull()
                            ? parseCircle(root.get("stagingSpawn"), Texts.tr("screen.tacz_pubg.map_staging.title")) : null);
            requireValid(map);
            return map;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.invalid_map_json", error.getMessage()), error);
        }
    }

    private static void requireValid(MapDefinition map) {
        List<String> errors = validate(map);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
    }

    private static JsonObject styleJson(MapVisualStyle style) {
        JsonObject value = new JsonObject();
        value.addProperty("safeZoneRgb", MapVisualStyle.hex(style.safeZoneRgb()));
        value.addProperty("blueZoneRgb", MapVisualStyle.hex(style.blueZoneRgb()));
        value.addProperty("bombingZoneRgb", MapVisualStyle.hex(style.bombingZoneRgb()));
        value.addProperty("lightningRgb", MapVisualStyle.hex(style.lightningRgb()));
        value.addProperty("mapBorderRgb", MapVisualStyle.hex(style.mapBorderRgb()));
        value.addProperty("flightPathRgb", MapVisualStyle.hex(style.flightPathRgb()));
        JsonObject loot = new JsonObject();
        style.lootTierRgb().forEach((id, rgb) -> loot.addProperty(id, MapVisualStyle.hex(rgb)));
        value.add("lootTierRgb", loot);
        value.addProperty("lootOverlapBlackLayers", style.lootOverlapBlackLayers());
        value.addProperty("blueMapOpacity", style.blueMapOpacity());
        value.addProperty("bombingMapOpacity", style.bombingMapOpacity());
        value.addProperty("blueWorldOpacity", style.blueWorldOpacity());
        value.addProperty("blueScreenOpacity", style.blueScreenOpacity());
        return value;
    }

    private static MapVisualStyle parseStyle(JsonElement element) {
        JsonObject style = object(element, Texts.tr("message.tacz_pubg.map_zone_model.map_colors"));
        keys(style, Set.of("safeZoneRgb", "blueZoneRgb", "bombingZoneRgb", "lightningRgb", "mapBorderRgb",
                "flightPathRgb", "blueMapOpacity", "bombingMapOpacity", "blueWorldOpacity", "blueScreenOpacity", "lootTierRgb", "lootOverlapBlackLayers"), Texts.tr("message.tacz_pubg.map_zone_model.map_colors"));
        var loot = new java.util.LinkedHashMap<>(MapVisualStyle.defaultLootTierRgb());
        if (style.has("lootTierRgb")) {
            JsonObject colors = object(style.get("lootTierRgb"), Texts.tr("message.tacz_pubg.map_zone_model.loot_colors"));
            for (var entry : colors.entrySet()) {
                com.example.examplemod.lootconfig.LootTier.fromId(entry.getKey());
                loot.put(entry.getKey(), MapVisualStyle.parseHex(string(colors, entry.getKey())));
            }
        }
        return new MapVisualStyle(MapVisualStyle.parseHex(string(style, "safeZoneRgb")),
                MapVisualStyle.parseHex(string(style, "blueZoneRgb")), MapVisualStyle.parseHex(string(style, "bombingZoneRgb")),
                MapVisualStyle.parseHex(string(style, "lightningRgb")), MapVisualStyle.parseHex(string(style, "mapBorderRgb")),
                MapVisualStyle.parseHex(string(style, "flightPathRgb")), number(style, "blueMapOpacity"),
                number(style, "bombingMapOpacity"), number(style, "blueWorldOpacity"), number(style, "blueScreenOpacity"), loot, style.has("lootOverlapBlackLayers") ? Math.toIntExact(integer(style, "lootOverlapBlackLayers")) : 5);
    }

    private static JsonObject circleJson(Circle circle) {
        JsonObject value = new JsonObject();
        value.addProperty("centerX", circle.centerX); value.addProperty("centerZ", circle.centerZ);
        value.addProperty("radius", circle.radius);
        return value;
    }

    private static Circle parseCircle(JsonElement element, String label) {
        JsonObject value = object(element, label);
        keys(value, Set.of("centerX", "centerZ", "radius"), label);
        return new Circle(number(value, "centerX"), number(value, "centerZ"), number(value, "radius"));
    }

    private static void circle(List<String> errors, String label, Circle circle, MapDefinition map) {
        if (circle == null) { errors.add(label + Texts.tr("message.tacz_pubg.map_zone_model.draw_circular_area")); return; }
        coordinate(errors, label + Texts.tr("message.tacz_pubg.map_zone_model.center_x"), circle.centerX);
        coordinate(errors, label + Texts.tr("message.tacz_pubg.map_zone_model.center_z"), circle.centerZ);
        if (!Double.isFinite(circle.radius) || circle.radius <= 0 || circle.radius > MAX_RADIUS) {
            errors.add(label + Texts.tr("message.tacz_pubg.map_zone_model.valid_radius") + MAX_RADIUS + "]");
        }
        if (!map.contains(circle.centerX, circle.centerZ)) errors.add(label + Texts.tr("message.tacz_pubg.map_zone_model.center_inside_map_rectangle"));
    }

    private static void coordinate(List<String> errors, String label, double value) {
        range(errors, label, value, -MAX_COORDINATE, MAX_COORDINATE);
    }

    private static void range(List<String> errors, String label, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) errors.add(label + Texts.tr("screen.tacz_pubg.loot_config.integer") + min + "–" + max + Texts.tr("message.tacz_pubg.map_zone_model.finite"));
    }

    private static void id(List<String> errors, String label, long value) {
        if (value < 0 || value == Long.MAX_VALUE) errors.add(label + Texts.tr("message.tacz_pubg.map_zone_model.valid_positive_id"));
    }

    private static void checkSize(String json) {
        if (json == null || json.length() > MAX_JSON_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.single_map_json_not_exceed_kib"));
        }
    }

    /** Strict streaming syntax plus duplicate-key, depth and array limits. */
    private static JsonElement readJson(String json) {
        checkSize(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement element = readElement(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.unexpected_content_after_map_json"));
            return element;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.invalid_map_json", error.getMessage()), error);
        }
    }

    private static JsonElement readElement(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.map_json_nesting_too_deep"));
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (value.has(key)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.duplicate_map_json_field", key));
                    value.add(key, readElement(reader, depth + 1));
                }
                reader.endObject();
                yield value;
            }
            case BEGIN_ARRAY -> {
                JsonArray value = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (value.size() >= MAX_BOMBING_ZONES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.map_json_array_too_long"));
                    value.add(readElement(reader, depth + 1));
                }
                reader.endArray();
                yield value;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_zone_model.check_map_json_value", reader.getPath()));
        };
    }

    private static JsonObject object(JsonElement element, String label) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.loot_config_model.object"));
        return element.getAsJsonObject();
    }

    private static void keys(JsonObject object, Set<String> allowed, String label) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.loot_config_model.check_field") + key);
    }

    private static JsonElement required(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.add_missing_field", key));
        return object.get(key);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.string"));
        return value.getAsString();
    }

    private static double number(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.number"));
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.map_zone_model.finite_number"));
        return result;
    }

    private static long integer(JsonObject object, String key) {
        number(object, key);
        try { return object.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.map_zone_model.valid_integer")); }
    }

    private static int intNumber(JsonObject object, String key) {
        long value = integer(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.map_zone_model.integer_out_range"));
        return (int) value;
    }
}
