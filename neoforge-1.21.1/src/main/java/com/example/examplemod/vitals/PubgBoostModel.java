package com.example.examplemod.vitals;

/**
 * Independent battle boost, normalized to maximum health rather than Minecraft hearts.
 * PUBG 42.1 shortened regeneration intervals without changing their total healing.
 * This model preserves the historically documented average rates and settles at server-tick
 * granularity; that granularity and the 2.5%/5% movement bonuses are adaptation choices,
 * not a claim that PUBG publishes its exact current implementation.
 */
public final class PubgBoostModel {
    public static final double MAX_BOOST = 100.0;
    private static final double TICKS_PER_BOOST_POINT = 60.0;
    private static final double ROUNDING_EPSILON = 1.0e-9;

    public record Step(double boost, double healedFraction) {}

    private PubgBoostModel() {}

    /** A single consumed boost adds to the current gauge, with no overflow beyond full. */
    public static double add(double current, double amount) {
        double value = normalized(current);
        return Double.isNaN(amount) ? value : normalized(value + amount);
    }

    /** Zero is unboosted; exact 20, 60 and 90 belong to the lower adjoining tier. */
    public static int tier(double boost) {
        double value = normalized(boost);
        if (value > 90.0) return 4;
        if (value > 60.0) return 3;
        if (value > 20.0) return 2;
        return value > 0.0 ? 1 : 0;
    }

    public static double speedBonus(double boost) {
        return switch (tier(boost)) {
            case 3 -> 0.025;
            case 4 -> 0.05;
            default -> 0.0;
        };
    }

    /**
     * Advances by active match ticks (20/s). Healing is a fraction of maximum health.
     * Integrating the piecewise rate makes a large step agree with repeated small steps
     * even when the step crosses several boost tiers.
     */
    public static Step advance(double boost, long ticks) {
        double before = normalized(boost);
        if (ticks <= 0 || before == 0.0) return new Step(before, 0.0);
        double after = Math.max(0.0, before - ticks / TICKS_PER_BOOST_POINT);
        if (after < ROUNDING_EPSILON) after = 0.0;
        return new Step(after, Math.max(0.0, healingPotential(before) - healingPotential(after)));
    }

    private static double healingPotential(double boost) {
        double weightedPoints = Math.min(boost, 20.0)
                + 2.0 * Math.min(Math.max(boost - 20.0, 0.0), 40.0)
                + 3.0 * Math.min(Math.max(boost - 60.0, 0.0), 30.0)
                + 4.0 * Math.min(Math.max(boost - 90.0, 0.0), 10.0);
        // Three seconds per boost point; one tier grants 1% maximum health per eight seconds.
        return weightedPoints * 3.0 / 800.0;
    }

    private static double normalized(double value) {
        if (!(value > 0.0)) return 0.0;
        return Math.min(value, MAX_BOOST);
    }
}
