package com.example.examplemod.gunbalance.ballistics;

import com.example.examplemod.localization.Texts;

/**
 * Stateless external-ballistics step, in blocks and ticks (1 block = 1 metre, 20 ticks = 1 second).
 * Gravity is split into two half impulses around an exact isotropic quadratic-drag drift.
 * The returned displacement is for this tick's existing TaCZ swept ray, not stored velocity.
 */
public final class BallisticTrajectory {
    public static final double TICKS_PER_SECOND = 20.0;
    public static final double EARTH_GRAVITY_MPS2 = 9.80665;
    public static final double EARTH_GRAVITY_PER_TICK = EARTH_GRAVITY_MPS2 / 400.0;

    private BallisticTrajectory() {}

    public record Step(double dx, double dy, double dz, double vx, double vy, double vz) {}

    public static Step step(double vx, double vy, double vz, double gravity, double dragPerMetre) {
        return step(vx, vy, vz, gravity, dragPerMetre, 1.0);
    }

    /** Fractional ticks are supported for offline convergence and reference-distance checks. */
    public static Step step(double vx, double vy, double vz, double gravity, double dragPerMetre, double ticks) {
        if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)
                || !Double.isFinite(gravity) || gravity < 0
                || !Double.isFinite(dragPerMetre) || dragPerMetre < 0
                || !Double.isFinite(ticks) || ticks <= 0) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ballistic_trajectory.ballistic_inputs_must_be_finite_with_nonnegative_gravity_drag_and_positive_time"));
        }
        double halfGravity = gravity * ticks * 0.5;
        double uy = vy - halfGravity;
        double speed = Math.hypot(Math.hypot(vx, uy), vz);
        double dragTime = dragPerMetre * speed * ticks;
        double retention = 1.0 / (1.0 + dragTime);
        // log1p(z)/z has limit 1. Avoid 0/0 for still projectiles or vacuum.
        double drift = dragTime == 0 ? ticks : ticks * Math.log1p(dragTime) / dragTime;
        return new Step(vx * drift, uy * drift, vz * drift,
                vx * retention, uy * retention - halfGravity, vz * retention);
    }

    /** Cancel TaCZ's live global multiplier at the common launch path, without editing its config. */
    public static float cancelGlobalSpeedMultiplier(float processedSpeed, double globalMultiplier) {
        if (!Float.isFinite(processedSpeed) || processedSpeed < 0
                || !Double.isFinite(globalMultiplier) || globalMultiplier <= 0) {
            return processedSpeed;
        }
        return (float) (processedSpeed / globalMultiplier);
    }
}
