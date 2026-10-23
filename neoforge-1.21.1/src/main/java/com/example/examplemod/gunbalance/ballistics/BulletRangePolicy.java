package com.example.examplemod.gunbalance.ballistics;

/** Only postpones TaCZ's existing age cleanup; never changes flight or introduces a range kill. */
public final class BulletRangePolicy {
    public static final double MINIMUM_HORIZONTAL_RANGE = 512.0;
    public static final double MIN_PROGRESS_PER_AGE_TICK = 0.0;
    public static final int MAX_STALLED_AGE_TICKS = 20;

    private BulletRangePolicy() {}

    /** Mutable per-projectile progress guard, called only when the native lifetime has already expired. */
    public static final class Progress {
        private boolean observed;
        private double previousX;
        private double previousZ;
        private int stalledTicks;

        boolean continues(double x, double z) {
            if (!observed) {
                observed = true;
                previousX = x;
                previousZ = z;
                return true;
            }
            double step = Math.hypot(x - previousX, z - previousZ);
            previousX = x;
            previousZ = z;
            stalledTicks = step > MIN_PROGRESS_PER_AGE_TICK ? 0 : stalledTicks + 1;
            return stalledTicks < MAX_STALLED_AGE_TICKS;
        }
    }

    /**
     * dx/dz are displacement from the launch origin; vx/vz are the next native horizontal velocity.
     * Quadratic drag remains in its existing integrator, so it uses the real progress/stall guard.
     * Constant native drag has a finite future endpoint v/(1-float32(1-friction)).
     */
    public static boolean postponeAgeCleanup(double dx, double dz, double vx, double vz,
            float nativeFriction, boolean usesQuadraticDrag, Progress progress) {
        if (progress == null || !Double.isFinite(dx) || !Double.isFinite(dz)
                || !Double.isFinite(vx) || !Double.isFinite(vz) || !Float.isFinite(nativeFriction)) return false;
        double currentDistance = Math.hypot(dx, dz);
        if (!Double.isFinite(currentDistance) || currentDistance >= MINIMUM_HORIZONTAL_RANGE) return false;
        double horizontalSpeed = Math.hypot(vx, vz);
        if (!Double.isFinite(horizontalSpeed) || horizontalSpeed == 0) return false;
        if (nativeFriction < 0 || nativeFriction >= 1) return false;

        if (!usesQuadraticDrag && nativeFriction > 0) {
            // TaCZ performs float subtraction before scaling its double motion vector.
            double loss = 1.0 - (double) (1.0F - nativeFriction);
            if (!Double.isFinite(loss)) return false;
            // Tiny positive source friction can round 1F-friction to exactly 1F. Native motion
            // then has no drag at all; do not reject its reachable slow flight as a finite tail.
            if (loss > 0) {
                double finalX = dx + vx / loss;
                double finalZ = dz + vz / loss;
                double finalDistance = Math.hypot(finalX, finalZ);
                // Future horizontal positions lie on this segment. If neither endpoint can reach the
                // threshold, extending time cannot help. Equality is approached only asymptotically.
                if (!Double.isFinite(finalDistance) || finalDistance <= MINIMUM_HORIZONTAL_RANGE) return false;
            }
        }
        return progress.continues(dx, dz);
    }
}
