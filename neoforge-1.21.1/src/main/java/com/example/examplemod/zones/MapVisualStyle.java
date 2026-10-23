package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;

import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import com.example.examplemod.lootconfig.LootTier;

/** Immutable per-map presentation, shared by its editor, Xaero overlays, world wall and HUD. */
public record MapVisualStyle(int safeZoneRgb, int blueZoneRgb, int bombingZoneRgb, int lightningRgb,
        int mapBorderRgb, int flightPathRgb, double blueMapOpacity, double bombingMapOpacity,
        double blueWorldOpacity, double blueScreenOpacity, Map<String, Integer> lootTierRgb, int lootOverlapBlackLayers) {
    public MapVisualStyle(int safeZoneRgb, int blueZoneRgb, int bombingZoneRgb, int lightningRgb,
            int mapBorderRgb, int flightPathRgb, double blueMapOpacity, double bombingMapOpacity,
            double blueWorldOpacity, double blueScreenOpacity, Map<String, Integer> lootTierRgb) {
        this(safeZoneRgb, blueZoneRgb, bombingZoneRgb, lightningRgb, mapBorderRgb, flightPathRgb,
                blueMapOpacity, bombingMapOpacity, blueWorldOpacity, blueScreenOpacity, lootTierRgb, 5);
    }
    public MapVisualStyle(int safeZoneRgb, int blueZoneRgb, int bombingZoneRgb, int lightningRgb,
            int mapBorderRgb, int flightPathRgb, double blueMapOpacity, double bombingMapOpacity,
            double blueWorldOpacity, double blueScreenOpacity) {
        this(safeZoneRgb, blueZoneRgb, bombingZoneRgb, lightningRgb, mapBorderRgb, flightPathRgb,
                blueMapOpacity, bombingMapOpacity, blueWorldOpacity, blueScreenOpacity, defaultLootTierRgb());
    }
    public static Map<String, Integer> defaultLootTierRgb() {
        var result = new LinkedHashMap<String, Integer>();
        for (LootTier tier : LootTier.values()) result.put(tier.id(), tier.rgb());
        return java.util.Collections.unmodifiableMap(result);
    }
    public int lootRgb(LootTier tier) { return lootTierRgb.get(tier.id()); }
    public static final MapVisualStyle DEFAULT = new MapVisualStyle(
            0xF7FFFF, 0x1549D8, 0xD82F37, 0xEAF5FF, 0x93A9B5, 0xD52B32, .24, .38, .34, .26);

    public MapVisualStyle {
        if (lootOverlapBlackLayers < 2 || lootOverlapBlackLayers > 256) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_visual_style.loot_floors_reach_solid_black_n"));
        var palette = new LinkedHashMap<>(defaultLootTierRgb());
        if (lootTierRgb == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_visual_style.loot_tier_colors_required"));
        lootTierRgb.forEach((key, value) -> { LootTier.fromId(key); rgb(value); palette.put(key, value); });
        lootTierRgb = java.util.Collections.unmodifiableMap(palette);
        rgb(safeZoneRgb); rgb(blueZoneRgb); rgb(bombingZoneRgb); rgb(lightningRgb);
        rgb(mapBorderRgb); rgb(flightPathRgb);
        opacity(blueMapOpacity); opacity(bombingMapOpacity); opacity(blueWorldOpacity); opacity(blueScreenOpacity);
    }

    /** Exactly six ASCII hexadecimal digits, optionally prefixed with '#'. No silent fallback. */
    public static int parseHex(String text) {
        if (text == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_visual_style.color_six_digit_hex_rgb_e_g_d"));
        String value = text.startsWith("#") ? text.substring(1) : text;
        if (!value.matches("[0-9A-Fa-f]{6}"))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_visual_style.color_six_digit_hex_rgb_e_g_d.parse_hex"));
        return Integer.parseInt(value, 16);
    }

    public static String hex(int rgb) { rgb(rgb); return String.format(Locale.ROOT, "%06X", rgb); }

    /** Rendering may modulate opacity for gradients; clamp it without changing the stored style. */
    public static int argb(int rgb, double opacity) {
        rgb(rgb);
        double bounded = Double.isFinite(opacity) ? Math.clamp(opacity, 0, 1) : 0;
        return ((int) Math.round(bounded * 255) << 24) | rgb;
    }

    private static void rgb(int value) {
        if (value < 0 || value > 0xFFFFFF) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_visual_style.color_rgb_ffffff"));
    }
    private static void opacity(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_visual_style.opacity_finite_between"));
    }
}
