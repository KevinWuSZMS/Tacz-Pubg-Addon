package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure quantity policy; world carriers and inventory stack limits are separate. */
public final class AmmoGrouping {
    public enum RemainderPolicy { KEEP_REMAINDER, ROUND_UP }

    /** PUBG supplies are thirty loose rounds per pile, rounded up from base magazine capacity. */
    public static int companionPileCount(int baseMagazineCapacity) {
        if (baseMagazineCapacity < 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ammo_grouping.base_magazine_capacity_positive"));
        return Math.floorDiv(baseMagazineCapacity - 1, 30) + 1;
    }

    private AmmoGrouping() {}

    public static List<Integer> split(int total, int magazineCapacity, RemainderPolicy policy, int maxPiles) {
        Objects.requireNonNull(policy);
        if (total < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ammo_grouping.total_ammo_not_negative"));
        if (magazineCapacity < 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ammo_grouping.magazine_capacity_positive"));
        if (maxPiles < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ammo_grouping.loot_carrier_budget_not_negative"));
        long count = (total + (long) magazineCapacity - 1) / magazineCapacity;
        if (count > maxPiles) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.ammo_grouping.splitting_item_stacks_would_exceed_region_s_remaining", maxPiles));
        List<Integer> result = new ArrayList<>((int) count);
        int remaining = total;
        while (remaining > 0) {
            int consumed = Math.min(remaining, magazineCapacity);
            result.add(policy == RemainderPolicy.ROUND_UP ? magazineCapacity : consumed);
            remaining -= consumed;
        }
        return List.copyOf(result);
    }
}
