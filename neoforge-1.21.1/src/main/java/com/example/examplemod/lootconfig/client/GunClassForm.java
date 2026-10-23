package com.example.examplemod.lootconfig.client;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.lootconfig.GunClass;
import com.example.examplemod.lootconfig.GunSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Nine raw percentages remain author-editable until the root's final save validation. */
final class GunClassForm {
    boolean automatic;
    final Map<String, String> percentages = new LinkedHashMap<>();

    GunClassForm(GunSelection source, Map<GunClass, Integer> counts) {
        automatic = source.isAutomatic();
        Map<GunClass, Double> probabilities = source.configuredProbabilities(counts);
        for (GunClass type : GunClass.values()) percentages.put(type.id(), percent(probabilities.getOrDefault(type, 0.0)));
    }

    GunSelection parse() {
        if (automatic) return GunSelection.defaults();
        var result = new LinkedHashMap<String, Double>();
        for (GunClass type : GunClass.values()) {
            String raw = percentages.get(type.id());
            try {
                double value = Double.parseDouble(raw.trim());
                if (!Double.isFinite(value) || value < 0 || value > 100) throw new NumberFormatException();
                result.put(type.id(), value / 100);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(type.label() + Texts.tr("screen.tacz_pubg.gun_class_form.enter_finite_percentage_your_input_was_preserved"));
            }
        }
        // Totals are validated only when the complete root document is saved.
        return new GunSelection(Map.copyOf(result));
    }

    List<String> values() {
        if (automatic) return List.of("automatic");
        var result = new ArrayList<String>(); result.add(Boolean.toString(automatic));
        for (GunClass type : GunClass.values()) result.add(percentages.get(type.id()));
        return List.copyOf(result);
    }

    boolean equalsValues(GunSelection source, Map<GunClass, Integer> counts) {
        return automatic && source.isAutomatic() || values().equals(new GunClassForm(source, counts).values());
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value).movePointRight(2).setScale(12, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
