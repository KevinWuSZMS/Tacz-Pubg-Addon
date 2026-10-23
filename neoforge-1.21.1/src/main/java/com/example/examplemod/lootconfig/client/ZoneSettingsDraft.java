package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.ZoneSettings;
import com.example.examplemod.zones.heavy.HeavyWeaponDamageCurve;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** An independent raw child draft. Invalid/incomplete strings survive resizing and picker visits. */
final class ZoneSettingsDraft {
    String ammoId, damage, radius, edgePercent, spawnHeight, blockInput = "", lootOverlapBlackLayers;
    boolean destroyBlocks, useGlobalVisualStyle;
    final List<String> passThroughBlocks = new ArrayList<>();
    final Map<String, Boolean> heavyWeaponTerrain = new LinkedHashMap<>();
    final Map<String, String[]> heavyWeaponDamageCurves = new LinkedHashMap<>();
    final String[] colors = new String[11], opacityPercent = new String[4];

    ZoneSettingsDraft(ZoneSettings source) {
        var bomb = source.bombing();
        ammoId = bomb.ammoId(); damage = number(bomb.damage()); radius = number(bomb.damageRadius());
        edgePercent = number(bomb.edgeDamageMultiplier() * 100); destroyBlocks = bomb.destroyBlocks();
        spawnHeight = bomb.spawnHeight() == Integer.MAX_VALUE ? Texts.tr("screen.tacz_pubg.zone_settings.world_height_limit") : Integer.toString(bomb.spawnHeight());
        passThroughBlocks.addAll(bomb.passThroughBlocks());
        heavyWeaponTerrain.putAll(source.heavyWeaponTerrain());
        source.heavyWeaponDamageCurves().forEach((id, curve) -> heavyWeaponDamageCurves.put(id, new String[]{number(curve.damageRadius()), number(curve.p()), number(curve.q())}));
        useGlobalVisualStyle = source.useGlobalVisualStyle();
        setStyle(source.visualStyle());
    }

    void setStyle(MapVisualStyle style) {
        int[] rgb = {style.safeZoneRgb(), style.blueZoneRgb(), style.bombingZoneRgb(), style.lightningRgb(),
                style.mapBorderRgb(), style.flightPathRgb()};
        double[] opacity = {style.blueMapOpacity(), style.bombingMapOpacity(), style.blueWorldOpacity(), style.blueScreenOpacity()};
        for (int i = 0; i < 6; i++) colors[i] = MapVisualStyle.hex(rgb[i]);
        var tiers = com.example.examplemod.lootconfig.LootTier.values();
        for (int i = 0; i < tiers.length; i++) colors[6 + i] = MapVisualStyle.hex(style.lootRgb(tiers[i]));
        lootOverlapBlackLayers = Integer.toString(style.lootOverlapBlackLayers());
        for (int i = 0; i < opacityPercent.length; i++) opacityPercent[i] = number(opacity[i] * 100);
    }

    ZoneSettings parse(Set<String> availableBombingAmmo) {
        return parse(availableBombingAmmo, null);
    }
    ZoneSettings parse(Set<String> availableBombingAmmo, Set<String> registeredBlocks) {
        if (!blockInput.isBlank()) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.click_add_put_block_id_list"));
        if (!availableBombingAmmo.contains(ammoId))
            throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.select_loaded_rocket_current_id", ammoId));
        double range = decimal(radius, Texts.tr("screen.tacz_pubg.zone_settings_draft.damage_radius"), 0, 128);
        if (range <= 0) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.enter_positive_radius_input_retained"));
        var bombing = new ZoneSettings.BombingProperties(ammoId, decimal(damage, Texts.tr("screen.tacz_pubg.zone_settings_draft.center_damage"), 0, 10_000),
                range, decimal(edgePercent, Texts.tr("screen.tacz_pubg.zone_settings_draft.edge_damage_ratio"), 0, 100) / 100, destroyBlocks,
                height(spawnHeight), blocks(registeredBlocks));
        return new ZoneSettings(bombing, useGlobalVisualStyle, style(), Map.copyOf(heavyWeaponTerrain), curves());
    }

    private Map<String, HeavyWeaponDamageCurve> curves() {
        Map<String, HeavyWeaponDamageCurve> result = new LinkedHashMap<>();
        heavyWeaponDamageCurves.forEach((id, raw) -> result.put(id, new HeavyWeaponDamageCurve(
                decimal(raw[0], Texts.tr("screen.tacz_pubg.zone_settings_draft.heavy_weapon_damage_radius"), 0, 128), decimal(raw[1], "p", 1, 2), decimal(raw[2], "q", 1, 2))));
        return Map.copyOf(result);
    }

    String[] curve(String id, double radius, double p, double q) {
        return heavyWeaponDamageCurves.computeIfAbsent(id, ignored -> new String[]{number(radius), number(p), number(q)});
    }

    void addBlock(String id, Set<String> registeredBlocks) {
        String trimmed = id.trim();
        if (trimmed.length() > 128 || ResourceLocation.tryParse(trimmed) == null || !trimmed.contains(":") || !registeredBlocks.contains(trimmed))
            throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.registered_block_id_e_g_minecraft_glass"));
        if (passThroughBlocks.contains(trimmed)) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.already_list", trimmed));
        if (passThroughBlocks.size() >= 256) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.most_pass_through_blocks_allowed"));
        passThroughBlocks.add(trimmed);
    }

    private List<String> blocks(Set<String> registeredBlocks) {
        if (passThroughBlocks.size() > 256) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.most_pass_through_blocks_allowed"));
        for (String id : passThroughBlocks) {
            if (id.length() > 128 || ResourceLocation.tryParse(id) == null || !id.contains(":") || registeredBlocks != null && !registeredBlocks.contains(id))
                throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.check_block_id", id));
        }
        return List.copyOf(passThroughBlocks);
    }

    private static int height(String raw) {
        if (raw.trim().equalsIgnoreCase("world") || Texts.matches("screen.tacz_pubg.zone_settings.world_height_limit", raw.trim())) return Integer.MAX_VALUE;
        try { int height = Integer.parseInt(raw.trim()); if (height >= -2032 && height <= 2031) return height; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.enter_world_height_limit_integer_y"));
    }

    MapVisualStyle style() {
        int[] rgb = new int[11];
        for (int i = 0; i < rgb.length; i++) rgb[i] = MapVisualStyle.parseHex(colors[i]);
        double[] alpha = new double[4];
        for (int i = 0; i < alpha.length; i++) alpha[i] = decimal(opacityPercent[i], Texts.tr("screen.tacz_pubg.zone_settings_draft.opacity"), 0, 100) / 100;
        Map<String, Integer> loot = new LinkedHashMap<>();
        var tiers = com.example.examplemod.lootconfig.LootTier.values();
        for (int i = 0; i < tiers.length; i++) loot.put(tiers[i].id(), rgb[6 + i]);
        int black;
        try { black = Integer.parseInt(lootOverlapBlackLayers.trim()); }
        catch (NumberFormatException issue) { throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.zone_settings_draft.loot_black_overlay_layers_n_integer")); }
        return new MapVisualStyle(rgb[0], rgb[1], rgb[2], rgb[3], rgb[4], rgb[5], alpha[0], alpha[1], alpha[2], alpha[3], loot, black);
    }

    List<String> values() {
        var result = new ArrayList<>(List.of(ammoId, damage, radius, edgePercent,
                Boolean.toString(destroyBlocks), Boolean.toString(useGlobalVisualStyle), spawnHeight, blockInput, lootOverlapBlackLayers));
        result.addAll(List.of(colors)); result.addAll(List.of(opacityPercent));
        result.add("blocks:"); result.addAll(passThroughBlocks);
        heavyWeaponTerrain.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add("terrain:" + entry.getKey() + "=" + entry.getValue()));
        heavyWeaponDamageCurves.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add("curve:" + entry.getKey() + "=" + String.join(",", entry.getValue())));
        return List.copyOf(result);
    }

    private static double decimal(String value, String label, double minimum, double maximum) {
        try {
            double result = Double.parseDouble(value.trim());
            if (Double.isFinite(result) && result >= minimum && result <= maximum) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(label + Texts.tr("screen.tacz_pubg.loot_config.integer") + number(minimum) + "–" + number(maximum) + Texts.tr("screen.tacz_pubg.loot_config.finite_input_retained"));
    }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
