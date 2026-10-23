package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** One probability roll per gun, then a uniform draw of distinct optional attachment kinds. */
public final class AttachmentCompanionSelection {
    private AttachmentCompanionSelection() {}

    public static <T> List<T> select(List<T> compatible, double chance, int kinds, RandomGenerator random) {
        Objects.requireNonNull(compatible);
        Objects.requireNonNull(random);
        if (!Double.isFinite(chance) || chance < 0 || chance > 1)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.attachment_companion_selection.companion_attachment_probability_between"));
        if (kinds < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.attachment_companion_selection.companion_attachment_type_count_not_negative"));
        if (chance == 0 || kinds == 0 || compatible.isEmpty()) return List.of();
        List<T> pool = new ArrayList<>(new LinkedHashSet<>(compatible));
        if (pool.contains(null)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.attachment_companion_selection.companion_attachment_candidates_not_empty"));
        // Roll once for the whole bundle. Placement failure never earns another roll or another kind.
        if (random.nextDouble() >= chance) return List.of();
        int count = Math.min(kinds, pool.size());
        for (int i = 0; i < count; i++) {
            int selected = i + random.nextInt(pool.size() - i);
            T old = pool.get(i);
            pool.set(i, pool.get(selected));
            pool.set(selected, old);
        }
        return List.copyOf(pool.subList(0, count));
    }
}
