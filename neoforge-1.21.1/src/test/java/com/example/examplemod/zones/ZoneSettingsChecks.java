package com.example.examplemod.zones;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.zones.ZoneSettings.BombingProperties;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Persistence, range, damage and presentation boundaries, without starting a game. */
public final class ZoneSettingsChecks {
    private static int checks;
    public static void main(String[] args) {
        var defaults = ZoneSettings.defaults();
        check(defaults.bombing().ammoId().equals("tacz:rpg_rocket"), "actual default rocket asset");
        check(!defaults.bombing().destroyBlocks() && !defaults.useGlobalVisualStyle(), "preserve terrain and per-map presentation defaults");
        var custom = new BombingProperties("suffuse:120mm", 100, 8, .2, true);
        near(custom.damageAt(0), 100, "center damage");
        near(custom.damageAt(4), 60, "linear midpoint");
        near(custom.damageAt(8), 20, "edge multiplier");
        check(custom.damageAt(8.0001) == 0 && custom.damageAt(-1) == 0 && custom.damageAt(Double.NaN) == 0,
                "strict geometric damage range");
        for (double distance = 0; distance <= 4; distance += .01)
            near(new BombingProperties("tacz:rpg_rocket", 10, 4, 1, false).damageAt(distance), 10, "edge one gives uniform damage");
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.1, 10001})
            rejects(() -> new BombingProperties("tacz:rpg_rocket", bad, 4, 0, false));
        for (double bad : new double[]{0, -.1, 128.00001, Double.NaN})
            rejects(() -> new BombingProperties("tacz:rpg_rocket", 10, bad, 0, false));
        for (double bad : new double[]{-.01, 1.0001, Double.NEGATIVE_INFINITY})
            rejects(() -> new BombingProperties("tacz:rpg_rocket", 10, 4, bad, false));
        for (String bad : new String[]{"", "rpg_rocket", "BAD:id", "test:bad space"})
            rejects(() -> new BombingProperties(bad, 10, 4, 0, false));

        var style = new MapVisualStyle(0x123456, 0x654321, 0xFF4455, 0xAABBCC, 0x112233, 0x445566, .1, .2, .3, .4);
        var settings = new ZoneSettings(custom, true, style);
        var g = LootConfigModel.General.defaults();
        var general = new LootConfigModel.General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(),
                g.samePlaneYTolerance(), g.minItemSpacing(), g.lootDensity(), g.ammoRadiusMin(), g.ammoRadiusMax(),
                g.companionAttachmentChance(), g.companionAttachmentKinds(), settings);
        var document = new LootConfigModel.Document(general, List.of());
        String json = LootConfigModel.toJson(document);
        check(document.equals(LootConfigModel.parse(json)), "all new values survive the real loot document codec");
        var legacy = JsonParser.parseString(json).getAsJsonObject();
        legacy.getAsJsonObject("general").remove("zoneSettings");
        check(LootConfigModel.parse(legacy.toString()).general().zoneSettings().equals(defaults), "legacy document acquires new defaults");
        var broken = JsonParser.parseString(json).getAsJsonObject();
        broken.getAsJsonObject("general").getAsJsonObject("zoneSettings").getAsJsonObject("bombing").addProperty("destroyBlocks", "true");
        final String stringBooleanJson = broken.toString();
        rejects(() -> LootConfigModel.parse(stringBooleanJson));
        broken = JsonParser.parseString(json).getAsJsonObject();
        broken.getAsJsonObject("general").getAsJsonObject("zoneSettings").addProperty("geometry", true);
        final String unknownFieldJson = broken.toString();
        rejects(() -> LootConfigModel.parse(unknownFieldJson));

        var map = new MapZoneModel.MapDefinition(7, "海岛", ResourceLocation.parse("minecraft:overworld"), -50, -60, 70, 80,
                new MapZoneModel.Circle(0, 0, 30), 1,
                List.of(new MapZoneModel.BombingZone(9, new MapZoneModel.Circle(10, 10, 12), 60, 99, 23)),
                new MapZoneModel.FlightPath(-40, -40, 60, 60), MapVisualStyle.DEFAULT);
        check(defaults.forDisplay(map) == map, "per-map presentation remains identical when global color is off");
        var display = settings.forDisplay(map);
        check(display.visualStyle().equals(style), "enabled global colors are sent to display");
        check(display.id() == map.id() && display.dimension().equals(map.dimension()) && display.name().equals(map.name()), "presentation preserves identity");
        check(display.minX() == map.minX() && display.maxX() == map.maxX() && display.minZ() == map.minZ() && display.maxZ() == map.maxZ()
                && display.safeZone().equals(map.safeZone()) && display.bombingZones().equals(map.bombingZones())
                && display.flightPath().equals(map.flightPath()) && display.blueDamagePerSecond() == map.blueDamagePerSecond(), "presentation preserves every geometric/gameplay field");
        check(map.visualStyle().equals(MapVisualStyle.DEFAULT), "saved map remains immutable");
        heightAndTerrainCodec();
        System.out.println("ZoneSettingsChecks: " + checks + " persistence, migration, damage and presentation assertions passed; no game launched.");
    }

    private static void heightAndTerrainCodec() {
        BombingProperties oldConstructor = new BombingProperties("tacz:rpg_rocket", 10, 4, 0, false);
        check(oldConstructor.spawnHeight() == BombingProperties.WORLD_HEIGHT && oldConstructor.passThroughBlocks().isEmpty(),
                "old bombing constructor gains world-height and empty block-pass defaults");
        check(ZoneSettings.defaults().heavyWeaponTerrain().isEmpty(), "default heavy terrain map inherits native behavior");
        check(new ZoneSettings(oldConstructor, false, MapVisualStyle.DEFAULT).heavyWeaponTerrain().isEmpty(),
                "old zone constructor retains heavy weapon defaults");
        for (int valid : new int[]{-2032, 0, 2031, BombingProperties.WORLD_HEIGHT})
            check(new BombingProperties("tacz:rpg_rocket", 10, 4, 0, false, valid, List.of()).spawnHeight() == valid,
                    "valid authored height " + valid);
        for (int invalid : new int[]{-2033, 2032, Integer.MIN_VALUE})
            rejects(() -> new BombingProperties("tacz:rpg_rocket", 10, 4, 0, false, invalid, List.of()));
        List<String> mutableBlocks = new ArrayList<>(List.of("minecraft:water", "minecraft:short_grass"));
        BombingProperties custom = new BombingProperties("cib:80", 25, 5, .25, false, 128, mutableBlocks);
        mutableBlocks.clear();
        check(custom.passThroughBlocks().equals(List.of("minecraft:water", "minecraft:short_grass")), "block pass list is defensively copied");
        immutable(() -> custom.passThroughBlocks().add("minecraft:stone"));
        rejects(() -> new BombingProperties("cib:80", 25, 5, .25, false, 128, List.of("minecraft:water", "minecraft:water")));
        for (String invalid : new String[]{"water", "BAD:block", "minecraft:bad space", ""})
            rejects(() -> new BombingProperties("cib:80", 25, 5, .25, false, 128, List.of(invalid)));
        List<String> maximumBlocks = new ArrayList<>();
        for (int index = 0; index < 256; index++) maximumBlocks.add("test:block_" + index);
        check(new BombingProperties("cib:80", 25, 5, .25, false, 128, maximumBlocks).passThroughBlocks().size() == 256,
                "block list authoring upper bound");
        maximumBlocks.add("test:overflow");
        rejects(() -> new BombingProperties("cib:80", 25, 5, .25, false, 128, maximumBlocks));

        Map<String, Boolean> mutableTerrain = new LinkedHashMap<>(Map.of("tacz:rpg7", true, "tacz:m320", false));
        ZoneSettings settings = new ZoneSettings(custom, true, MapVisualStyle.DEFAULT, mutableTerrain);
        mutableTerrain.put("tacz:rpg7", false);
        check(settings.heavyWeaponTerrain().get("tacz:rpg7") && !settings.heavyWeaponTerrain().get("tacz:m320"),
                "each heavy weapon terrain switch is captured independently");
        immutable(() -> settings.heavyWeaponTerrain().put("cib:dzj08", true));
        rejects(() -> new ZoneSettings(custom, false, MapVisualStyle.DEFAULT, Map.of("RPG7", true)));
        var g = LootConfigModel.General.defaults();
        var general = new LootConfigModel.General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(),
                g.samePlaneYTolerance(), g.minItemSpacing(), g.lootDensity(), g.ammoRadiusMin(), g.ammoRadiusMax(),
                g.companionAttachmentChance(), g.companionAttachmentKinds(), settings);
        var document = new LootConfigModel.Document(general, List.of());
        String json = LootConfigModel.toJson(document);
        check(LootConfigModel.parse(json).equals(document), "new height/block/terrain fields survive full document serialization");

        var old = JsonParser.parseString(json).getAsJsonObject();
        var oldZones = old.getAsJsonObject("general").getAsJsonObject("zoneSettings");
        oldZones.remove("heavyWeaponTerrain");
        oldZones.getAsJsonObject("bombing").remove("spawnHeight");
        oldZones.getAsJsonObject("bombing").remove("passThroughBlocks");
        old.getAsJsonObject("general").remove("gunSelection");
        var migrated = LootConfigModel.parse(old.toString()).general();
        check(migrated.zoneSettings().bombing().equals(new BombingProperties("cib:80", 25, 5, .25, false))
                && migrated.zoneSettings().heavyWeaponTerrain().isEmpty() && migrated.gunSelection().isAutomatic(),
                "schema-8-shaped document gains only defaults for the newly introduced fields");
        check(migrated.zoneSettings().useGlobalVisualStyle() == settings.useGlobalVisualStyle()
                && migrated.zoneSettings().visualStyle().equals(settings.visualStyle()), "migration preserves global presentation settings");

        for (String mutation : List.of("fractionalHeight", "stringHeight", "nullHeight", "nonArrayBlocks", "numericBlock",
                "duplicateBlock", "nullBlock", "stringTerrain", "invalidTerrainId", "nullTerrain", "unknownBombingField")) {
            var broken = JsonParser.parseString(json).getAsJsonObject();
            var zones = broken.getAsJsonObject("general").getAsJsonObject("zoneSettings");
            var bombing = zones.getAsJsonObject("bombing");
            switch (mutation) {
                case "fractionalHeight" -> bombing.addProperty("spawnHeight", 128.5);
                case "stringHeight" -> bombing.addProperty("spawnHeight", "128");
                case "nullHeight" -> bombing.add("spawnHeight", com.google.gson.JsonNull.INSTANCE);
                case "nonArrayBlocks" -> bombing.addProperty("passThroughBlocks", "minecraft:water");
                case "numericBlock" -> bombing.getAsJsonArray("passThroughBlocks").add(4);
                case "duplicateBlock" -> bombing.getAsJsonArray("passThroughBlocks").add("minecraft:water");
                case "nullBlock" -> bombing.getAsJsonArray("passThroughBlocks").add(com.google.gson.JsonNull.INSTANCE);
                case "stringTerrain" -> zones.getAsJsonObject("heavyWeaponTerrain").addProperty("tacz:rpg7", "true");
                case "invalidTerrainId" -> zones.getAsJsonObject("heavyWeaponTerrain").addProperty("RPG7", true);
                case "nullTerrain" -> zones.add("heavyWeaponTerrain", com.google.gson.JsonNull.INSTANCE);
                case "unknownBombingField" -> bombing.addProperty("gravity", .06);
                default -> throw new AssertionError("Unknown test mutation");
            }
            final String malformed = broken.toString();
            rejects(() -> LootConfigModel.parse(malformed));
        }
    }

    private static void immutable(Runnable action) {
        checks++;
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Captured authoring collection was mutable");
    }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid authoring input was accepted");
    }
    private static void near(double actual, double expected, String label) { check(Math.abs(actual - expected) < 1e-8, label); }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
