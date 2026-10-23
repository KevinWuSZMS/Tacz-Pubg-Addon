package com.example.examplemod.vitals;

/** Necessary boost boundaries only; no game, registry bootstrap or world startup. */
public final class PubgBoostChecks {
    private static int checks;

    private PubgBoostChecks() {}

    public static void main(String[] args) {
        thresholds();
        durationAndHealing();
        additionAndInvalidInputs();
        partitionedAdvance();
        System.out.println("PubgBoostChecks: " + checks + " assertions passed");
    }

    private static void thresholds() {
        check(PubgBoostModel.tier(0) == 0, "Empty boost has no tier");
        check(PubgBoostModel.tier(0.1) == 1 && PubgBoostModel.tier(20) == 1, "First tier ends at 20");
        check(PubgBoostModel.tier(20.1) == 2 && PubgBoostModel.tier(60) == 2, "Second tier ends at 60");
        check(PubgBoostModel.tier(60.1) == 3 && PubgBoostModel.tier(90) == 3, "Third tier ends at 90");
        check(PubgBoostModel.tier(90.1) == 4 && PubgBoostModel.tier(100) == 4, "Fourth tier ends at full");
        near(PubgBoostModel.speedBonus(60), 0, "No speed bonus below the third tier");
        near(PubgBoostModel.speedBonus(60.1), 0.025, "Third tier speed bonus");
        near(PubgBoostModel.speedBonus(90), 0.025, "Exact 90 remains third tier");
        near(PubgBoostModel.speedBonus(90.1), 0.05, "Fourth tier speed bonus");
    }

    private static void durationAndHealing() {
        var firstSecond = PubgBoostModel.advance(100, 20);
        near(firstSecond.boost(), 100 - 1.0 / 3.0, "One second consumes one third boost point");
        near(firstSecond.healedFraction(), 0.005, "Full boost heals one half percent maximum health per second");
        var whole = PubgBoostModel.advance(100, 6000);
        near(whole.boost(), 0, "Full gauge empties in 300 seconds");
        near(whole.healedFraction(), 0.8625, "Full gauge integrates all four historical average rates");
        var drink = PubgBoostModel.advance(40, 2400);
        near(drink.boost(), 0, "Energy drink lasts 120 seconds");
        near(drink.healedFraction(), 0.225, "Energy drink heals about 23 percent before health cap");
        var crossed = PubgBoostModel.advance(91, 120);
        near(crossed.boost(), 89, "Crossing fourth to third tier reduces boost correctly");
        near(crossed.healedFraction(), 0.02625, "Crossing step integrates each tier for its own duration");
        near(PubgBoostModel.advance(100, Long.MAX_VALUE).healedFraction(), whole.healedFraction(),
                "Long elapsed interval cannot heal beyond the gauge");
        near(PubgBoostModel.advance(0, 100).healedFraction(), 0, "No boost grants no regeneration");
        check(PubgBoostModel.advance(40, 0).equals(new PubgBoostModel.Step(40, 0)), "Paused clock does not consume boost");
        check(PubgBoostModel.advance(40, -1).equals(new PubgBoostModel.Step(40, 0)), "Negative time does not consume boost");
    }

    private static void additionAndInvalidInputs() {
        near(PubgBoostModel.add(0, 40), 40, "Energy drink addition");
        near(PubgBoostModel.add(40, 60), 100, "Painkiller stacks up to full");
        near(PubgBoostModel.add(80, 40), 100, "Excess boost is capped");
        near(PubgBoostModel.add(10, -40), 0, "Gauge cannot become negative");
        near(PubgBoostModel.add(Double.NaN, 40), 40, "Invalid starting gauge is normalized");
        near(PubgBoostModel.add(40, Double.NaN), 40, "Invalid addition cannot erase valid boost");
        check(PubgBoostModel.tier(Double.NaN) == 0, "Invalid gauge has no active effects");
        near(PubgBoostModel.advance(Double.POSITIVE_INFINITY, 6000).boost(), 0, "Infinite gauge is capped before advancing");
    }

    private static void partitionedAdvance() {
        for (double start : new double[] { 100, 90.1, 60.1, 20.1, 40 }) {
            var whole = PubgBoostModel.advance(start, 4217);
            double remaining = start;
            double healed = 0;
            for (int tick = 0; tick < 4217; tick++) {
                var step = PubgBoostModel.advance(remaining, 1);
                remaining = step.boost();
                healed += step.healedFraction();
            }
            near(remaining, whole.boost(), "Tick partition preserves boost across tier boundaries");
            near(healed, whole.healedFraction(), "Tick partition preserves integrated regeneration");
        }
        double gauge = 100;
        for (int tick = 0; tick < 6000; tick++) gauge = PubgBoostModel.advance(gauge, 1).boost();
        check(gauge == 0.0, "Repeated server ticks leave no floating-point residue after 300 seconds");
    }

    private static void near(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1.0e-9,
                message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
