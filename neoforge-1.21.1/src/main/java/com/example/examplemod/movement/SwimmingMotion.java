package com.example.examplemod.movement;

/** Vertical input keeps vanilla water thrust and drag, without look or wall-jump carry-over. */
public final class SwimmingMotion {
    private SwimmingMotion() { }

    public static double retainedVertical(double previous, int direction, double swimSpeed) {
        if (direction == 0) return 0;
        // Vanilla jump/sink adds .04 * SWIM_SPEED and water retains .8 of vertical velocity.
        double limit = 0.04 * Math.max(0, swimSpeed) * 0.8 / (1.0 - 0.8);
        return direction > 0 ? Math.clamp(previous, 0, limit) : Math.clamp(previous, -limit, 0);
    }
}
