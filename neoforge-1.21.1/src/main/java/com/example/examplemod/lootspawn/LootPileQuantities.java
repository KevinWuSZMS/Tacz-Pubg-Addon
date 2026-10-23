package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.List;

/** Logical pile amounts are independent of the legal size of an inventory ItemStack. */
public final class LootPileQuantities {
    public static final int MAX_QUANTITY = 4_096;

    private LootPileQuantities() {}

    /** Version 1 stored a full legal stack; version 2 stores a count-one base and an explicit amount. */
    public static int restored(int version, boolean hasQuantity, int quantity, int legacyItemCount, int stackLimit) {
        if (stackLimit < 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_pile_quantities.item_stack_limit_positive"));
        return switch (version) {
            case 1 -> {
                if (legacyItemCount < 1 || legacyItemCount > stackLimit || legacyItemCount > MAX_QUANTITY) {
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_pile_quantities.invalid_item_count_legacy_ground_loot"));
                }
                yield legacyItemCount;
            }
            case 2 -> {
                if (!hasQuantity || legacyItemCount != 1 || quantity < 1 || quantity > MAX_QUANTITY) {
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_pile_quantities.new_ground_loot_requires_base_item_count_logical"));
                }
                yield quantity;
            }
            default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_pile_quantities.unsupported_ground_loot_save_version", version));
        };
    }

    /** Inventory insertion batches, not separate world entities. Empty piles produce no batches. */
    public static List<Integer> batches(int quantity, int stackLimit) {
        if (quantity < 0 || quantity > MAX_QUANTITY) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_pile_quantities.ground_loot_count_between"));
        if (stackLimit < 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_pile_quantities.item_stack_limit_positive"));
        List<Integer> result = new ArrayList<>();
        for (int remaining = quantity; remaining > 0;) {
            int batch = Math.min(remaining, stackLimit);
            result.add(batch);
            remaining -= batch;
        }
        return List.copyOf(result);
    }
}
