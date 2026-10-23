package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Null probabilities retain the dynamic, count-based default when gun packs change. */
public record GunSelection(Map<String, Double> probabilities) {
    public static final String TOTAL_ERROR_PREFIX = Texts.tr("message.tacz_pubg.gun_selection.gun_class_probability_total");
    public GunSelection {
        if (probabilities != null) probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
    }
    public static GunSelection defaults() { return new GunSelection(null); }
    public static GunSelection automatic() { return defaults(); }
    public boolean isAutomatic() { return probabilities == null; }
    public static boolean isTotalError(String text) { return text != null && text.startsWith(TOTAL_ERROR_PREFIX); }
    public List<String> validate() {
        if (isAutomatic()) return List.of();
        List<String> errors = new ArrayList<>();
        if (!probabilities.keySet().equals(GunClass.IDS)) errors.add(Texts.tr("message.tacz_pubg.gun_selection.all_nine_gun_classes_required"));
        double total = 0;
        for (GunClass type : GunClass.values()) {
            Double value = probabilities.get(type.id());
            if (value == null || !Double.isFinite(value) || value < 0 || value > 1)
                errors.add(type.label() + Texts.tr("message.tacz_pubg.gun_selection.enter_valid_probability"));
            else total += value;
        }
        if (errors.isEmpty() && Math.abs(total - 1) > LootConfigModel.CATEGORY_TOTAL_TOLERANCE)
            errors.add(TOTAL_ERROR_PREFIX + Texts.tr("message.tacz_pubg.gun_selection.total"));
        return List.copyOf(errors);
    }
    public Map<GunClass, Double> configuredProbabilities(Map<GunClass, Integer> counts) {
        EnumMap<GunClass, Double> result = new EnumMap<>(GunClass.class);
        long total = counts.values().stream().mapToLong(Integer::longValue).sum();
        for (GunClass type : GunClass.values()) {
            Double value = probabilities == null ? (total == 0 ? 0 : counts.getOrDefault(type, 0) / (double) total) : probabilities.get(type.id());
            if (value == null || !Double.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException(type.label() + Texts.tr("message.tacz_pubg.gun_selection.enter_valid_probability"));
            result.put(type, value);
        }
        return Collections.unmodifiableMap(result);
    }
}
