package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Pure rules for the approved nine magazines; existing attachment leaves are retained. */
public final class PubgMagazineRules {
    public static final Set<String> MAGAZINE_IDS = Set.of(
            "tacz_pubg_workbench:quickdraw_mag_rifle", "tacz_pubg_workbench:extended_mag_rifle",
            "tacz_pubg_workbench:extended_quickdraw_mag_rifle", "tacz_pubg_workbench:quickdraw_mag_smg",
            "tacz_pubg_workbench:extended_mag_smg", "tacz_pubg_workbench:extended_quickdraw_mag_smg",
            "tacz_pubg_workbench:quickdraw_mag_sniper", "tacz_pubg_workbench:extended_mag_sniper",
            "tacz_pubg_workbench:extended_quickdraw_mag_sniper");

    private PubgMagazineRules() {}

    public static Set<String> forFamilies(Collection<String> families) {
        Set<String> result = new HashSet<>();
        for (String family : families) {
            if (!Set.of("rifle", "smg", "sniper").contains(family))
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_rules.unknown_magazine_family") + family);
            for (String variant : Set.of("quickdraw", "extended", "extended_quickdraw"))
                result.add("tacz_pubg_workbench:" + variant + "_mag_" + family);
        }
        return Set.copyOf(result);
    }

    /** Flatten nested tags first so initial mode removes indirect grants as well. */
    public static Set<String> applyPolicy(Collection<String> original, Map<String, Set<String>> tags,
            Collection<String> allowedMagazines, boolean enabled) {
        Set<String> result = new LinkedHashSet<>();
        expand(original, tags, new HashSet<>(), result);
        result.removeAll(MAGAZINE_IDS);
        if (enabled) for (String id : allowedMagazines)
            if (MAGAZINE_IDS.contains(id)) result.add(id);
        return Set.copyOf(result);
    }

    private static void expand(Collection<String> values, Map<String, Set<String>> tags,
            Set<String> visited, Set<String> result) {
        for (String value : values) {
            if (!value.startsWith("#")) result.add(value);
            else if (visited.add(value)) expand(tags.getOrDefault(value.substring(1), Set.of()), tags, visited, result);
        }
    }

    public static boolean hasMagazineSlot(JsonObject data) {
        JsonElement types = data.get("allow_attachment_types");
        if (types == null || !types.isJsonArray()) return false;
        for (JsonElement type : types.getAsJsonArray())
            if (type.isJsonPrimitive() && "extended_mag".equalsIgnoreCase(type.getAsString())) return true;
        return false;
    }

    /** Inspect only; no capacities, reload values or original slots are rewritten. */
    public static boolean supportsNativeMagazineLevels(JsonObject data) {
        if (!hasMagazineSlot(data)) return false;
        JsonElement capacities = data.get("extended_mag_ammo_amount");
        if (capacities == null || !capacities.isJsonArray() || capacities.getAsJsonArray().size() < 3) return false;
        try {
            for (int i = 0; i < 3; i++) {
                JsonElement value = capacities.getAsJsonArray().get(i);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                        || value.getAsBigDecimal().stripTrailingZeros().scale() > 0
                        || value.getAsBigDecimal().signum() <= 0
                        || value.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return false;
            }
            JsonElement reload = data.get("reload");
            if (reload != null && reload.isJsonObject()) {
                JsonElement type = reload.getAsJsonObject().get("type");
                if (type != null && (!type.isJsonPrimitive() || !"magazine".equalsIgnoreCase(type.getAsString()))) return false;
            }
            JsonElement builtin = data.get("builtin_attachments");
            return builtin == null || !builtin.isJsonObject()
                    || !builtin.getAsJsonObject().has("extended_mag");
        } catch (RuntimeException invalidData) { return false; }
    }
}
