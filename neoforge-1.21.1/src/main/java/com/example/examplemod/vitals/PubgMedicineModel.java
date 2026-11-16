package com.example.examplemod.vitals;

/** PC battle-royale medical values, expressed as fractions of the player's actual maximum health. */
public final class PubgMedicineModel {
    public enum Kind {
        BANDAGE(60, 58, .75, .12, 0),
        FIRST_AID_KIT(120, 40, .75, 0, 0),
        MED_KIT(120, 0, 1, 0, 0),
        ENERGY_DRINK(80, 0, 0, 0, 40),
        PAINKILLER(120, 0, 0, 0, 60),
        ADRENALINE_SYRINGE(120, 0, 0, 0, 100);

        private final int useTicks, recoveryTicks, boost;
        private final double ceiling, fraction;
        Kind(int useTicks, int recoveryTicks, double ceiling, double fraction, int boost) {
            this.useTicks = useTicks; this.recoveryTicks = recoveryTicks;
            this.ceiling = ceiling; this.fraction = fraction; this.boost = boost;
        }
        public int useTicks() { return useTicks; }
        public int recoveryTicks() { return recoveryTicks; }
        public int recoverySteps() { return recoveryTicks > 0 ? 5 : 1; }
        public int boost() { return boost; }
        public double ceiling() { return ceiling; }
        public boolean isBoost() { return boost > 0; }
        public boolean canUse(double health, double maxHealth, double currentBoost) {
            if (!Double.isFinite(health) || !Double.isFinite(maxHealth) || health <= 0 || maxHealth <= 0) return false;
            return isBoost() ? Double.isFinite(currentBoost) && currentBoost >= 0 && currentBoost < 100
                    : health < maxHealth * ceiling - 1.0e-6;
        }
        /** Snapshot the budget once: later damage must not enlarge a completed first-aid treatment. */
        public double healingBudget(double health, double maxHealth) {
            if (isBoost() || !canUse(health, maxHealth, 0)) return 0;
            double missing = maxHealth * ceiling - health;
            return fraction > 0 ? Math.min(missing, maxHealth * fraction) : missing;
        }
        /** The cast preview and server recovery use the same five equal portions. */
        public double firstStepBudget(double health, double maxHealth) {
            return healingBudget(health, maxHealth) / recoverySteps();
        }
    }
    /** A fixed budget split across discrete pulses; reaching the ceiling discards unused healing. */
    public record Recovery(double budget, double ceiling, int duration, long start, long advanced,
                           int steps) {
        public Recovery(double budget, double ceiling, int duration, long start, long advanced) {
            this(budget, ceiling, duration, start, advanced, 5);
        }
        public Recovery {
            if (!Double.isFinite(budget) || budget < 0 || !Double.isFinite(ceiling)
                    || ceiling <= 0 || duration <= 0 || advanced < 0 || advanced > duration
                    || steps < 1 || steps > duration)
                throw new IllegalArgumentException("Invalid medical recovery");
        }
        private double scheduled(long ticks) {
            // Integer deadlines retain exactly five pulses even when 58 ticks cannot divide by five.
            return budget * (ticks * steps / duration) / steps;
        }
        public double remainingBudget() { return Math.max(0, budget - scheduled(advanced)); }
        public double nextBudget() {
            return Math.min(remainingBudget(), budget / steps);
        }
        public Step advance(long clock, double health, double maxHealth) {
            long elapsed = Math.min(duration, Math.max(0, clock - start));
            elapsed = Math.max(advanced, elapsed);
            double scheduled = scheduled(elapsed) - scheduled(advanced);
            double allowed = Double.isFinite(health) && Double.isFinite(maxHealth)
                    ? Math.max(0, maxHealth * ceiling - health) : 0;
            return new Step(new Recovery(budget, ceiling, duration, start, elapsed, steps),
                    Math.min(scheduled, allowed));
        }
        public boolean finished() { return advanced >= duration; }
    }
    public record Step(Recovery recovery, double healing) {}
    public static boolean completed(long started, long now, int requiredTicks, int usingTicks) {
        // Vanilla finishes on the Nth entity tick, before the Nth ServerTickEvent.Post combat-clock increment.
        return requiredTicks > 0 && now - started >= requiredTicks - 1L && usingTicks >= requiredTicks - 1;
    }
    public static boolean movedTooFar(double squaredDistance) {
        return !Double.isFinite(squaredDistance) || squaredDistance > .25;
    }
    private PubgMedicineModel() {}
}
