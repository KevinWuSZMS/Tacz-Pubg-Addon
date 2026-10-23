package com.example.examplemod.gunbalance.client;

import java.util.Random;

/** Pure recoil checks: no game, renderer, event bus, player or TaCZ classes are initialized. */
public final class PersistentRecoilChecks {
    private static int checks;

    public static void main(String[] args) {
        exactExtrema();
        curveValidation();
        frameIndependence();
        stackingAndMouseInput();
        boundedQueue();
        randomExtrema();
        System.out.println("Persistent recoil checks passed: " + checks + " assertions.");
    }

    private static void exactExtrema() {
        // A recoil curve can overshoot between authored keyframes; endpoints alone miss it.
        var pitch = PersistentRecoilMath.peak(new double[] {0, 100}, new double[][] {{0, .04, -.0004}}, false);
        near(pitch.value(), 1, "interior uplift exact");
        near(pitch.timeMillis(), 50, "interior uplift time exact");
        var yaw = PersistentRecoilMath.peak(new double[] {0, 100}, new double[][] {{0, -.08, .0008}}, true);
        near(yaw.value(), -2, "leftward yaw keeps sign");
        near(yaw.timeMillis(), 50, "leftward yaw peak time exact");
        var negativePitch = PersistentRecoilMath.peak(new double[] {0, 100}, new double[][] {{0, -.08, .0008}}, false);
        near(negativePitch.value(), 0, "downward-only curve does not invent muzzle uplift");
        var cubic = PersistentRecoilMath.peak(new double[] {0, 3}, new double[][] {{0, 3, -3, 1}}, true);
        near(cubic.value(), 9, "cubic global endpoint wins");
        var repeatedRoot = PersistentRecoilMath.peak(new double[] {0, 2}, new double[][] {{0, 0, 0, 1}}, true);
        near(repeatedRoot.value(), 8, "repeated derivative root remains finite");
        var tie = PersistentRecoilMath.peak(new double[] {0, 1, 2}, new double[][] {{0, 1}, {1, -2}}, true);
        near(tie.value(), 1, "absolute tie retains earliest direction");
        near(tie.timeMillis(), 1, "absolute tie retains earliest time");
        for (double time : new double[] {0, .001, 1, 10, 49.999, 50, 100, 1000, 1e9}) {
            double displacement = pitch.displacement(time);
            check(displacement >= 0 && displacement <= 1, "no recovery or overshoot in retained ramp");
        }
        near(pitch.displacement(1000), 1, "peak persists beyond original curve end");
    }

    private static void curveValidation() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            near(PersistentRecoilMath.peak(new double[] {0, bad}, new double[][] {{1}}, false).value(), 0,
                    "invalid knot ignored");
            near(PersistentRecoilMath.peak(new double[] {0, 1}, new double[][] {{bad}}, true).value(), 0,
                    "invalid coefficient ignored");
        }
        near(PersistentRecoilMath.peak(null, null, false).value(), 0, "absent recoil preserved");
        near(PersistentRecoilMath.peak(new double[] {0, 0}, new double[][] {{1}}, false).value(), 0, "duplicate knot ignored");
        near(PersistentRecoilMath.peak(new double[] {-1, 1}, new double[][] {{1}}, false).value(), 0, "negative domain ignored");
        near(PersistentRecoilMath.peak(new double[] {0, 1}, new double[][] {{1, 2, 3, 4, 5}}, true).value(), 0,
                "unsupported polynomial ignored");
    }

    private static void frameIndependence() {
        for (double step : new double[] {.5, 1, 4, 16.666, 33.333, 100, 1000}) {
            PersistentRecoilState state = new PersistentRecoilState();
            state.add(new PersistentRecoilMath.Peak(2.25, 83), new PersistentRecoilMath.Peak(-.75, 120), 0);
            double pitch = 0, yaw = 0;
            for (double time = 0; time < 1000; time += step) {
                var delta = state.sample(time);
                check(delta.pitch() >= -1e-12, "pitch never automatically returns at any frame rate");
                pitch += delta.pitch();
                yaw += delta.yaw();
                var repeated = state.sample(time);
                near(repeated.pitch(), 0, "same render time is idempotent");
                near(repeated.yaw(), 0, "same render time does not repeat jitter");
            }
            var tail = state.sample(5000);
            near(pitch + tail.pitch(), 2.25, "same final pitch across frame rates");
            near(yaw + tail.yaw(), -.75, "same final signed yaw across frame rates");
            check(state.pendingCount() == 0, "finished impulses removed without reversing orientation");
            near(state.sample(10000).pitch(), 0, "idle frame does not recenter");
        }
        PersistentRecoilState state = new PersistentRecoilState();
        state.add(new PersistentRecoilMath.Peak(2, 100), PersistentRecoilMath.Peak.ZERO, 0);
        var half = state.sample(50);
        near(half.pitch(), 1, "first half of smoothstep");
        near(state.sample(0).pitch(), 0, "partial tick rollback cannot reverse recoil");
        near(state.sample(Double.NaN).pitch(), 0, "invalid sample cannot poison clock");
        near(state.sample(100).pitch(), 1, "resume after repeated/invalid sample");
    }

    private static void stackingAndMouseInput() {
        PersistentRecoilState state = new PersistentRecoilState();
        state.add(new PersistentRecoilMath.Peak(3, 100), new PersistentRecoilMath.Peak(-1, 100), 0);
        var first = state.sample(25);
        double playerPitch = 20 - first.pitch(), playerYaw = 40 - first.yaw();
        // Player counter-steers between render updates. The state owns no absolute player baseline.
        playerPitch += 2;
        playerYaw -= 7;
        state.add(new PersistentRecoilMath.Peak(4, 150), new PersistentRecoilMath.Peak(2, 50), 25);
        var rest = state.sample(1000);
        playerPitch -= rest.pitch();
        playerYaw -= rest.yaw();
        near(playerPitch, 15, "manual counter-steering remains in final aim");
        near(playerYaw, 32, "mouse yaw input remains with two opposing shot impulses");
        state.add(new PersistentRecoilMath.Peak(9, 100), PersistentRecoilMath.Peak.ZERO, 1000);
        state.sample(1025);
        state.clear();
        near(state.sample(5000).pitch(), 0, "switching gun/scheme drops only future motion");
        state.clear();
        state.add(new PersistentRecoilMath.Peak(2, 100), PersistentRecoilMath.Peak.ZERO, 0);
        near(state.sample(100).pitch(), 2, "new world clock is independent after reset");
    }

    private static void boundedQueue() {
        PersistentRecoilState state = new PersistentRecoilState();
        for (int i = 0; i < 10000; i++) {
            state.add(new PersistentRecoilMath.Peak(1, 1000), new PersistentRecoilMath.Peak(-.5, 1000), 0);
            check(state.pendingCount() <= 256, "arbitrary long author curves cannot grow the queue forever");
        }
        var total = state.sample(2000);
        near(total.pitch(), 10000, "queue pressure does not silently lose fired shots");
        near(total.yaw(), -5000, "queue pressure retains signed yaw");
    }

    private static void randomExtrema() {
        Random random = new Random(18102026);
        for (int i = 0; i < 500; i++) {
            double[] c = {random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2,
                    random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2};
            double domain = .01 + random.nextDouble() * 4;
            var pitch = PersistentRecoilMath.peak(new double[] {0, domain}, new double[][] {c}, false);
            var yaw = PersistentRecoilMath.peak(new double[] {0, domain}, new double[][] {c}, true);
            for (int j = 0; j <= 128; j++) {
                double t = domain * j / 128;
                double value = ((c[3] * t + c[2]) * t + c[1]) * t + c[0];
                check(pitch.value() + 1e-10 >= value, "analytic pitch contains every dense sample");
                check(Math.abs(yaw.value()) + 1e-10 >= Math.abs(value), "analytic yaw contains every dense sample");
            }
        }
    }

    private static void near(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-8, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
