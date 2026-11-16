package com.example.examplemod.lean;

public final class LeanMotionChecks {
    private static int checks;
    public static void main(String[] args) {
        long t = 7_000_000_000L, duration = LeanMotion.TRAVEL_NANOS;
        var motion = new LeanMotion();
        motion.target(1, t);
        near(motion.sample(t), 0, "no discontinuity on press");
        near(motion.sample(t + duration / 2), .5, "half transition");
        near(motion.sample(t + duration), 1, "full right");
        near(motion.sample(t + duration * 20), 1, "bounded hold");
        for (int fps : new int[]{20, 30, 60, 144, 240}) {
            for (int frame = 0; frame < fps; frame++) motion.sample(t + (long) (frame * 1.0e9 / fps));
            near(motion.sample(t + duration / 2), .5, "sampling independent of FPS and call order");
        }
        motion.target(-1, t + duration);
        near(motion.sample(t + duration), 1, "switch begins at actual position");
        near(motion.sample(t + duration * 2), 0, "right to left crosses neutral");
        near(motion.sample(t + duration * 3), -1, "right to left full travel");
        motion.target(0, t + duration * 3);
        near(motion.sample(t + duration * 4), 0, "release returns neutral");
        motion.correct(.2, -1, t);
        near(motion.sample(t), .2, "wall reversal begins at clamped position");
        near(motion.sample(t + duration / 2), -.3, "wall reversal does not travel from hidden unclipped position");
        motion.reset(t);
        near(motion.sample(t + duration), 0, "lifecycle reset has no residual lean");
        var input = new LeanMotion.Input();
        input.press(-1); check(input.direction() == -1, "Q presses left");
        input.press(1); check(input.direction() == 1, "new side wins");
        input.press(-1); check(input.direction() == 1, "repeat cannot steal ownership");
        input.release(1); check(input.direction() == 0, "old held key cannot reassert");
        input.release(-1); input.press(-1); check(input.direction() == -1, "new edge reactivates");
        input.reset(); check(input.direction() == 0, "lost focus clears keys");
        near(LeanMotion.limited(1, 1, .4), .4, "remote extrapolation cannot cross an authoritative right wall");
        near(LeanMotion.limited(-1, -1, .4), -.4, "remote extrapolation cannot cross an authoritative left wall");
        near(LeanMotion.limited(.3, 0, 0), .3, "return-to-neutral retains its transition");
        near(LeanMotion.limited(.3, -1, .1), .3, "switching side retains the old-side transition");
        System.out.println("LeanMotionChecks: " + checks + " passed");
    }
    private static void near(double actual, double expected, String name) { check(Math.abs(actual - expected) < 1.0e-10, name); }
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
}
