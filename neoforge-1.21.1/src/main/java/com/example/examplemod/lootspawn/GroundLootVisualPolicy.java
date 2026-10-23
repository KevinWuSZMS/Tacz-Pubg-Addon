package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

/** Shared numeric rule used by the live model renderer and the footprint generator. */
public final class GroundLootVisualPolicy {
    public static final int PROFILE_VERSION = 4;
    /** TaCZ SlotModel is a one-block quad before the item's own native GROUND transform. */
    public static final double ICON_SIZE = 1;

    private GroundLootVisualPolicy() {}

    public static double extraScale(double width, double height, double depth) {
        if (!Double.isFinite(width + height + depth) || width < 0 || height < 0 || depth < 0)
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_visual_policy.invalid_model_extents"));
        double longest = Math.max(width, Math.max(height, depth));
        if (longest <= 1e-7) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_visual_policy.empty_model"));
        // Preserve every authored GROUND size, including zero-thickness planes and extensions.
        return 1;
    }
}
