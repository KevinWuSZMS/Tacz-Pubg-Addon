package com.example.examplemod.zones;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Random;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Palette validation, legacy migration, persistence and native payload round trips. No world is opened. */
public final class MapVisualStyleChecks {
    private static int checks;
    public static void main(String[] args) throws Exception {
        check(MapVisualStyle.parseHex("#aBc123") == 0xABC123, "hash-prefixed RGB accepts mixed case");
        check(MapVisualStyle.hex(7).equals("000007"), "leading zeros retained");
        for (String invalid : new String[] {"", "#", "12345", "1234567", "GG0000", " 1549D8", "1549D8 ", "##1549D8", "0x1549D8", "１２３４５６"})
            rejects(() -> MapVisualStyle.parseHex(invalid));
        rejects(() -> MapVisualStyle.parseHex(null));
        rejects(() -> MapVisualStyle.hex(-1)); rejects(() -> MapVisualStyle.hex(0x1000000));
        for (double opacity : new double[] {-.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY})
            rejects(() -> style(0x123456, opacity));
        var random = new Random(1234);
        for (int i = 0; i < 1024; i++) {
            int rgb = random.nextInt(0x1000000);
            check(MapVisualStyle.parseHex(MapVisualStyle.hex(rgb)) == rgb, "all 24 RGB bits round trip");
            check((MapVisualStyle.argb(rgb, .38) & 0xFFFFFF) == rgb, "opacity never changes RGB");
        }
        check(MapVisualStyle.argb(0x112233, 0) == 0x00112233, "zero is transparent");
        check(MapVisualStyle.argb(0x112233, 1) == 0xFF112233, "one is opaque");
        check(MapVisualStyle.argb(0x112233, .38) == 0x61112233, "alpha rounds from fraction once");
        var customColors = new java.util.LinkedHashMap<>(MapVisualStyle.defaultLootTierRgb());
        customColors.put("low", 0x123abc);
        MapVisualStyle custom = new MapVisualStyle(0xFFFFFF, 0x123456, 0xE01020, 0xF5FCFF, 0x808080, 0xFFDD00,
                .51, .43, .32, .29, customColors, 9);
        check(custom.lootRgb(com.example.examplemod.lootconfig.LootTier.LOW) == 0x123abc && custom.lootOverlapBlackLayers() == 9,
                "custom tier palette and overlap n retained");
        var dimension = ResourceLocation.parse("minecraft:overworld");
        MapDefinition map = new MapDefinition(0, "彩色地图", dimension, -50, -50, 50, 50,
                new Circle(0, 0, 20), 1, List.of(), new FlightPath(-50, 0, 50, 0), custom);
        String json = MapZoneModel.toJson(map);
        check(MapZoneModel.parse(json).equals(map), "all six colors/four alpha values survive JSON");
        var legacy = JsonParser.parseString(json).getAsJsonObject();
        legacy.remove("visualStyle");
        check(MapZoneModel.parse(legacy.toString()).visualStyle().equals(MapVisualStyle.DEFAULT), "old maps gain defaults");
        check(new MapDefinition(0, "旧地图", dimension, 0, 0, 10, 10, null, 1, List.of()).visualStyle()
                .equals(MapVisualStyle.DEFAULT), "old constructors remain compatible");
        for (String corrupt : new String[] {json.replace("\"blueZoneRgb\":\"123456\"", "\"blueZoneRgb\":\"123\""),
                json.replace("\"blueZoneRgb\":\"123456\"", "\"blueZoneRgb\":123456"),
                json.replace("\"blueMapOpacity\":0.51", "\"blueMapOpacity\":1.1"),
                json.replace("\"blueMapOpacity\":0.51", "\"blueMapOpacity\":\"0.51\""),
                json.replace("\"visualStyle\":{", "\"visualStyle\":{\"unknown\":7,"),
                json.replace("\"visualStyle\":{", "\"visualStyle\":{\"blueZoneRgb\":\"FFFFFF\","),
                json.replace("\"visualStyle\":{", "\"visualStyle\":null,\"unknown\":{")}) {
            rejects(() -> MapZoneModel.parse(corrupt));
        }
        MapZoneSavedData data = new MapZoneSavedData();
        var saved = data.saveMap(0, map);
        check(saved.visualStyle().equals(custom), "ID assignment preserves colors");
        var encoded = data.save(new CompoundTag(), null);
        var restored = MapZoneSavedData.load(encoded, null);
        check(restored.loadError().isEmpty() && restored.map(saved.id()).visualStyle().equals(custom), "NBT round trip");
        var withFlight = MapZoneCommands.class.getDeclaredMethod("withFlightPath", MapDefinition.class, FlightPath.class);
        withFlight.setAccessible(true);
        check(((MapDefinition) withFlight.invoke(null, saved, null)).visualStyle().equals(custom), "flight removal preserves custom colors");
        var copy = MapZoneCommands.class.getDeclaredMethod("copy", MapDefinition.class, Circle.class, List.class);
        copy.setAccessible(true);
        check(((MapDefinition) copy.invoke(null, saved, new Circle(1, 1, 20), List.of())).visualStyle().equals(custom), "new circle commands preserve custom colors");
        var packet = new MapOverlayPayloads.MapSnapshot(7, dimension, MapZoneModel.toJson(saved));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MapOverlayPayloads.MapSnapshot.CODEC.encode(buffer, packet);
            var decoded = MapOverlayPayloads.MapSnapshot.CODEC.decode(buffer);
            check(MapZoneModel.parse(decoded.json()).visualStyle().equals(custom), "read-only viewer receives complete style");
            check(buffer.readableBytes() == 0, "packet consumed exactly");
        } finally { buffer.release(); }
        System.out.println("Map visual style checks passed: " + checks);
    }

    private static MapVisualStyle style(int blue, double opacity) {
        return new MapVisualStyle(0xFFFFFF, blue, 0xE01020, 0xF5FCFF, 0x808080, 0xFFDD00,
                opacity, .43, .32, .29);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable task) {
        try { task.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid style unexpectedly accepted");
    }
}
