package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.resources.ResourceLocation;
import com.example.examplemod.zones.heavy.HeavyWeaponDamageCurve;

/** World-wide projectile properties and optional presentation; never map geometry. */
public record ZoneSettings(BombingProperties bombing, boolean useGlobalVisualStyle, MapVisualStyle visualStyle,
        Map<String, Boolean> heavyWeaponTerrain, Map<String, HeavyWeaponDamageCurve> heavyWeaponDamageCurves) {
    public ZoneSettings(BombingProperties bombing, boolean useGlobalVisualStyle, MapVisualStyle visualStyle,
            Map<String, Boolean> heavyWeaponTerrain) {
        this(bombing, useGlobalVisualStyle, visualStyle, heavyWeaponTerrain, Map.of());
    }
    public ZoneSettings(BombingProperties bombing, boolean useGlobalVisualStyle, MapVisualStyle visualStyle) {
        this(bombing, useGlobalVisualStyle, visualStyle, Map.of(), Map.of());
    }
    public ZoneSettings {
        Objects.requireNonNull(bombing, Texts.tr("message.tacz_pubg.zone_settings.bombardment_properties_required"));
        Objects.requireNonNull(visualStyle, Texts.tr("message.tacz_pubg.zone_settings.display_colors_required"));
        Objects.requireNonNull(heavyWeaponTerrain, Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_terrain_settings_required"));
        if (heavyWeaponTerrain.size() > 8192) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_terrain_settings_allow_most_guns"));
        heavyWeaponTerrain.forEach((id, value) -> {
            checkId(id, Texts.tr("screen.tacz_pubg.zone_settings.heavy_weapons"));
            Objects.requireNonNull(value, Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_terrain_toggles_booleans"));
        });
        heavyWeaponTerrain = Map.copyOf(heavyWeaponTerrain);
        Objects.requireNonNull(heavyWeaponDamageCurves, Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_damage_curves_required"));
        if (heavyWeaponDamageCurves.size() > 8192) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_curves_allow_most_guns"));
        heavyWeaponDamageCurves.forEach((id, value) -> { checkId(id, Texts.tr("screen.tacz_pubg.zone_settings.heavy_weapons")); Objects.requireNonNull(value, Texts.tr("message.tacz_pubg.zone_settings.curve_parameters_required")); });
        heavyWeaponDamageCurves = Map.copyOf(heavyWeaponDamageCurves);
    }

    public static ZoneSettings defaults() {
        return new ZoneSettings(BombingProperties.defaults(), false, MapVisualStyle.DEFAULT);
    }

    public MapVisualStyle effectiveStyle(MapVisualStyle mapStyle) {
        return useGlobalVisualStyle ? visualStyle : Objects.requireNonNull(mapStyle);
    }

    /** Presentation-only snapshot for viewers; the persisted map and match geometry stay immutable. */
    public MapZoneModel.MapDefinition forDisplay(MapZoneModel.MapDefinition map) {
        if (!useGlobalVisualStyle || map == null || visualStyle.equals(map.visualStyle())) return map;
        return new MapZoneModel.MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                map.safeZone(), map.blueDamagePerSecond(), map.bombingZones(), map.flightPath(), visualStyle, map.stagingSpawn());
    }

    public record BombingProperties(String ammoId, double damage, double damageRadius,
            double edgeDamageMultiplier, boolean destroyBlocks, int spawnHeight, List<String> passThroughBlocks) {
        public static final int WORLD_HEIGHT = Integer.MAX_VALUE;
        public BombingProperties(String ammoId, double damage, double damageRadius,
                double edgeDamageMultiplier, boolean destroyBlocks) {
            this(ammoId, damage, damageRadius, edgeDamageMultiplier, destroyBlocks, WORLD_HEIGHT, List.of());
        }
        public BombingProperties {
            if (ammoId == null || ammoId.length() > 128 || ResourceLocation.tryParse(ammoId) == null
                    || !ammoId.contains(":")) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.bombardment_ammo_full_namespace_path_id"));
            bounded(damage, 0, 10000, Texts.tr("screen.tacz_pubg.zone_settings.center_damage_hp"));
            if (!Double.isFinite(damageRadius) || damageRadius <= 0 || damageRadius > 128)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.explosion_damage_radius_blocks"));
            bounded(edgeDamageMultiplier, 0, 1, Texts.tr("screen.tacz_pubg.zone_settings_draft.edge_damage_ratio"));
            if (spawnHeight != WORLD_HEIGHT && (spawnHeight < -2032 || spawnHeight > 2031))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.projectile_spawn_height_world_height_limit_integer_y"));
            Objects.requireNonNull(passThroughBlocks, Texts.tr("message.tacz_pubg.zone_settings.pass_through_block_list_required"));
            if (passThroughBlocks.size() > 256) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.pass_through_block_list_allows_most_entries"));
            passThroughBlocks.forEach(id -> checkId(id, Texts.tr("message.tacz_pubg.zone_settings.pass_through_block")));
            if (Set.copyOf(passThroughBlocks).size() != passThroughBlocks.size())
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.keep_one_entry_per_pass_through_block_type"));
            passThroughBlocks = List.copyOf(passThroughBlocks);
        }
        public static BombingProperties defaults() {
            return new BombingProperties("tacz:rpg_rocket", 10, 4, 0, false);
        }
        /** Geometric damage before vanilla obstruction, armour and damage events. Outside is always zero. */
        public double damageAt(double distance) {
            if (!Double.isFinite(distance) || distance < 0 || distance > damageRadius) return 0;
            return damage * (1 - (1 - edgeDamageMultiplier) * distance / damageRadius);
        }
    }

    /** Strict authoring format; absence of this whole object is handled by the legacy loader. */
    public static ZoneSettings parse(JsonElement element) {
        JsonObject root = object(element, Set.of("bombing", "useGlobalVisualStyle", "visualStyle", "heavyWeaponTerrain", "heavyWeaponDamageCurves"),
                Set.of("bombing", "useGlobalVisualStyle", "visualStyle"), Texts.tr("message.tacz_pubg.zone_settings.zone_general_settings"));
        JsonObject b = object(root.get("bombing"), Set.of("ammoId", "damage", "damageRadius", "edgeDamageMultiplier", "destroyBlocks", "spawnHeight", "passThroughBlocks"),
                Set.of("ammoId", "damage", "damageRadius", "edgeDamageMultiplier", "destroyBlocks"), Texts.tr("screen.tacz_pubg.zone_settings.bombardment_properties"));
        JsonObject v = object(root.get("visualStyle"), Set.of("safeZoneRgb", "blueZoneRgb", "bombingZoneRgb", "lightningRgb",
                "mapBorderRgb", "flightPathRgb", "blueMapOpacity", "bombingMapOpacity", "blueWorldOpacity", "blueScreenOpacity", "lootTierRgb", "lootOverlapBlackLayers"),
                Set.of("safeZoneRgb", "blueZoneRgb", "bombingZoneRgb", "lightningRgb", "mapBorderRgb", "flightPathRgb",
                        "blueMapOpacity", "bombingMapOpacity", "blueWorldOpacity", "blueScreenOpacity"), Texts.tr("message.tacz_pubg.zone_settings.display_colors"));
        return new ZoneSettings(new BombingProperties(string(b, "ammoId"), number(b, "damage"), number(b, "damageRadius"),
                number(b, "edgeDamageMultiplier"), bool(b, "destroyBlocks"), b.has("spawnHeight") ? integer(b, "spawnHeight") : BombingProperties.WORLD_HEIGHT,
                b.has("passThroughBlocks") ? blockList(b.get("passThroughBlocks")) : List.of()), bool(root, "useGlobalVisualStyle"),
                new MapVisualStyle(integer(v, "safeZoneRgb"), integer(v, "blueZoneRgb"), integer(v, "bombingZoneRgb"),
                        integer(v, "lightningRgb"), integer(v, "mapBorderRgb"), integer(v, "flightPathRgb"),
                        number(v, "blueMapOpacity"), number(v, "bombingMapOpacity"), number(v, "blueWorldOpacity"), number(v, "blueScreenOpacity"),
                        v.has("lootTierRgb") ? lootColors(v.get("lootTierRgb")) : MapVisualStyle.defaultLootTierRgb(),
                        v.has("lootOverlapBlackLayers") ? integer(v, "lootOverlapBlackLayers") : 5),
                root.has("heavyWeaponTerrain") ? terrainMap(root.get("heavyWeaponTerrain")) : Map.of(),
                root.has("heavyWeaponDamageCurves") ? curveMap(root.get("heavyWeaponDamageCurves")) : Map.of());
    }

    private static List<String> blockList(JsonElement element) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() > 256)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.pass_through_blocks_array_most_entries"));
        return java.util.stream.StreamSupport.stream(element.getAsJsonArray().spliterator(), false).map(value -> {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.pass_through_blocks_full_namespace_path_ids"));
            return value.getAsString();
        }).toList();
    }
    private static Map<String, Boolean> terrainMap(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_terrain_settings_object"));
        Map<String, Boolean> result = new LinkedHashMap<>();
        element.getAsJsonObject().keySet().forEach(id -> result.put(id, bool(element.getAsJsonObject(), id)));
        return result;
    }
    private static Map<String, Integer> lootColors(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.loot_tier_colors_object"));
        Map<String, Integer> result = new LinkedHashMap<>();
        element.getAsJsonObject().keySet().forEach(id -> result.put(id, integer(element.getAsJsonObject(), id)));
        return result;
    }
    private static Map<String, HeavyWeaponDamageCurve> curveMap(JsonElement element) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_damage_curves_object"));
        Map<String, HeavyWeaponDamageCurve> result = new LinkedHashMap<>();
        element.getAsJsonObject().asMap().forEach((id, value) -> {
            var curve = object(value, Set.of("damageRadius", "p", "q"), Texts.tr("message.tacz_pubg.zone_settings.heavy_weapon_damage_curve"));
            result.put(id, new HeavyWeaponDamageCurve(number(curve, "damageRadius"), number(curve, "p"), number(curve, "q")));
        });
        return result;
    }
    private static void checkId(String id, String label) {
        if (id == null || id.length() > 128 || !id.contains(":") || ResourceLocation.tryParse(id) == null)
            throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.zone_settings.full_namespace_path_id"));
    }

    private static JsonObject object(JsonElement value, Set<String> keys, String label) {
        return object(value, keys, keys, label);
    }
    private static JsonObject object(JsonElement value, Set<String> allowed, Set<String> required, String label) {
        if (value == null || !value.isJsonObject() || !allowed.containsAll(value.getAsJsonObject().keySet())
                || !value.getAsJsonObject().keySet().containsAll(required))
            throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.match_config_model.check_field_names_completeness"));
        return value.getAsJsonObject();
    }
    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.string"));
        return value.getAsString();
    }
    private static boolean bool(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.zone_settings.boolean"));
        return value.getAsBoolean();
    }
    private static double number(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.map_zone_model.finite_number"));
        return value.getAsDouble();
    }
    private static int integer(JsonObject object, String key) {
        number(object, key);
        try { return object.get(key).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.integer")); }
    }
    private static void bounded(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.loot_config.integer") + min + "–" + max + Texts.tr("message.tacz_pubg.map_zone_model.finite"));
    }
}
