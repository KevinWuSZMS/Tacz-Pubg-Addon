package com.example.examplemod.armor.client;

/** Head-local placement; applied before the item's mirrored rendering scale, after head pose. */
public final class HelmetWearPlacement {
    public static final float BASE_SCALE = .90f;
    // FIXED-space roof difference against the mean of the first/third tier pixel helmets.
    // At worn 3.1 this raises the second tier by .90 * 3.1 * .043 = .11997 blocks.
    public static final double TIER_2_PIXEL_LIFT = .043;
    private HelmetWearPlacement() {}
    public static double y(int tier, boolean pixel, double multiplier) {
        return -.25 - (pixel && tier == 2 ? BASE_SCALE * multiplier * TIER_2_PIXEL_LIFT : 0);
    }
}
