package com.example.examplemod.lootspawn.client;

/** Timing/yaw checks executable with plain javac/java; no game or mod runtime is loaded. */
public final class DroppedLootTransitionChecks {
    private static int checks;

    private DroppedLootTransitionChecks() {}

    public static void main(String[] args) {
        for (long tick = 0; tick <= 10; tick++) {
            check(!DroppedLootTransitionMath.canHandoff(tick, 1, false), "unsynchronized target never replaces the visible ghost");
        }
        for (long tick = 0; tick < 10; tick++) {
            check(DroppedLootTransitionMath.canHandoff(tick, 0, true), "ready metadata immediately reuses the ghost's visible interpolation segment");
        }
        for (long tick = 1; tick < 10; tick++) {
            check(DroppedLootTransitionMath.canHandoff(tick, 1, true), "ready landed target remains eligible without an extra wait");
        }
        check(!DroppedLootTransitionMath.canHandoff(1, -1, true), "uninitialized readiness cannot hand off");
        check(!DroppedLootTransitionMath.canHandoff(10, 1, true), "timeout owns the exact ten-tick boundary");
        check(DroppedLootTransitionMath.canHandoff(9, 2, true), "ready carrier remains handoff eligible before expiry");
        check(!DroppedLootTransitionMath.canHandoff(Long.MAX_VALUE, 1, true), "stale carrier cannot hand off after its lifetime");
        check(!DroppedLootTransitionMath.expired(0), "the first notification tick is live");
        check(!DroppedLootTransitionMath.expired(1), "normal immediate handoff occurs before expiry");
        check(!DroppedLootTransitionMath.expired(9), "ghost survives up to the wait boundary");
        check(DroppedLootTransitionMath.expired(10), "ghost expires at ten ticks");
        check(DroppedLootTransitionMath.expired(Long.MAX_VALUE), "late notifications cannot outlive the cap");

        check(DroppedLootTransitionMath.spawnYaw(0) == 0, "zero yaw remains fixed");
        check(DroppedLootTransitionMath.spawnYaw(90) == 90, "quarter-turn yaw remains fixed");
        check(DroppedLootTransitionMath.spawnYaw(-90) == 270, "signed packet yaw is equivalent after normalization");
        check(DroppedLootTransitionMath.spawnYaw(360) == 0, "one full turn has identical orientation");
        check(DroppedLootTransitionMath.spawnYaw(359.9F) == 358.59375F, "fallback matches the exact spawn packet quantization");
        for (float yaw : new float[] {1, 39.7F, 90, 179.9F, 270, 359.9F}) {
            float expected = DroppedLootTransitionMath.spawnYaw(yaw);
            check(expected == DroppedLootTransitionMath.spawnYaw(expected), "ghost-to-ground yaw does not change on handoff");
        }
        rejects(() -> DroppedLootTransitionMath.spawnYaw(Float.NaN));
        rejects(() -> DroppedLootTransitionMath.spawnYaw(Float.POSITIVE_INFINITY));
        check(DroppedLootTransitionMath.HANDOFF_TICKS == 0, "transition adds neither a one-tick hold nor the old 150 ms landing hold");
        check(DroppedLootTransitionMath.MAX_WAIT_TICKS == 10, "missing target metadata remains strictly bounded");
        check(DroppedLootTransitionMath.canHandoff(8, 0, true), "metadata at tick eight immediately splices the existing blend");
        check(DroppedLootTransitionMath.canHandoff(9, 1, true), "metadata at tick eight hands off at tick nine");
        check(!DroppedLootTransitionMath.canHandoff(10, 1, true), "metadata at tick nine expires cleanly at tick ten");
        System.out.println("Dropped loot transition checks passed: " + checks);
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("nonfinite yaw must be rejected");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
