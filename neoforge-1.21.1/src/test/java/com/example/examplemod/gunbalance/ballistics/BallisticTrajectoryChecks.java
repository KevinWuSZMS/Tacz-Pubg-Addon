package com.example.examplemod.gunbalance.ballistics;

import java.util.Random;

/** Pure Java flight checks; does not create a game instance or a world. */
public final class BallisticTrajectoryChecks {
    private static int assertions;

    public static void main(String[] args) {
        near(BallisticTrajectory.EARTH_GRAVITY_PER_TICK, 0.024516625, 1e-14, "Earth gravity unit conversion");
        Random random = new Random(180202610L);
        for (int trial = 0; trial < 1000; trial++) {
            double vx0 = random.nextDouble() * 100 - 50;
            double vy0 = random.nextDouble() * 100 - 50;
            double vz0 = random.nextDouble() * 100 - 50;
            double gravity = random.nextDouble() * 0.05;
            double vx = vx0, vy = vy0, vz = vz0, x = 0, y = 0, z = 0;
            int ticks = 1 + random.nextInt(200);
            for (int n = 0; n < ticks; n++) {
                var step = BallisticTrajectory.step(vx, vy, vz, gravity, 0);
                x += step.dx(); y += step.dy(); z += step.dz();
                vx = step.vx(); vy = step.vy(); vz = step.vz();
            }
            near(x, vx0 * ticks, 1e-8, "vacuum X");
            near(y, vy0 * ticks - gravity * ticks * ticks / 2, 1e-8, "vacuum parabola Y");
            near(z, vz0 * ticks, 1e-8, "vacuum Z");
            near(vy, vy0 - gravity * ticks, 1e-10, "stored true velocity, not last displacement");
        }
        for (int trial = 0; trial < 1000; trial++) {
            double v0 = (100 + random.nextDouble() * 1000) / 20;
            double retainedRatio = .3 + random.nextDouble() * .69;
            double k = Math.log(1 / retainedRatio) / 100;
            double arrivalTicks = Math.expm1(k * 100) / (k * v0);
            var at100 = BallisticTrajectory.step(v0, 0, 0, 0, k, arrivalTicks);
            near(at100.dx(), 100, 1e-10, "reference-distance drift");
            near(at100.vx(), v0 * retainedRatio, 1e-10, "V100 reference reproduced");
            double vx = v0, x = 0;
            int pieces = 1 + random.nextInt(20);
            for (int n = 0; n < pieces; n++) {
                var part = BallisticTrajectory.step(vx, 0, 0, 0, k, arrivalTicks / pieces);
                x += part.dx(); vx = part.vx();
            }
            near(x, at100.dx(), 1e-9, "drag drift independent of time partition");
            near(vx, at100.vx(), 1e-10, "drag retention independent of time partition");
        }
        for (double mps : new double[] {200, 330, 370, 715, 800, 910, 1100}) {
            for (double global : new double[] {.1, .5, 1, 2, 4, 10}) {
                float processed = (float) (mps * global / 20);
                near(BallisticTrajectory.cancelGlobalSpeedMultiplier(processed, global), mps / 20, 1e-5,
                        "global multiplier canceled once");
            }
        }
        near(BallisticTrajectory.cancelGlobalSpeedMultiplier(32, 0), 32, 0, "invalid global is not divided");
        near(BallisticTrajectory.cancelGlobalSpeedMultiplier(32, Double.NaN), 32, 0, "NaN global is not divided");
        var still = BallisticTrajectory.step(0, 0, 0, 0, .003);
        near(still.dx() + still.dy() + still.dz() + still.vx() + still.vy() + still.vz(), 0, 0, "still finite drag limit");
        for (double mps : new double[] {200, 370, 715, 910, 1100}) {
            double k = .001;
            var coarse = integrate(mps / 20, k, 1, 40);
            var fine = integrate(mps / 20, k, .5, 80);
            // Gravity split converges without requiring expensive additional in-game ray casts.
            near(coarse[0], fine[0], .001, "20 vs 40 Hz horizontal convergence");
            near(coarse[1], fine[1], .004, "20 vs 40 Hz vertical convergence");
        }
        boolean invalid = false;
        try { BallisticTrajectory.step(Double.NaN, 0, 0, .01, .001); } catch (IllegalArgumentException expected) { invalid = true; }
        require(invalid, "nonfinite trajectory input rejected");
        invalid = false;
        try { BallisticTrajectory.step(40, 0, 0, -.01, .001); } catch (IllegalArgumentException expected) { invalid = true; }
        require(invalid, "negative gravity rejected");
        System.out.println("Ballistic trajectory checks passed: " + assertions);
    }

    private static double[] integrate(double vx, double k, double dt, int count) {
        double vy = 0, x = 0, y = 0;
        for (int i = 0; i < count; i++) {
            var step = BallisticTrajectory.step(vx, vy, 0, BallisticTrajectory.EARTH_GRAVITY_PER_TICK, k, dt);
            x += step.dx(); y += step.dy(); vx = step.vx(); vy = step.vy();
        }
        return new double[] {x, y};
    }

    private static void near(double actual, double expected, double tolerance, String label) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                label + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
