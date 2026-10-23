package com.example.examplemod.gunbalance.client;

/** Render-boundary regression checks; neither Minecraft nor TaCZ is initialized. */
public final class FirstPersonPoseSafetyChecks {
    private static int checks;

    public static void main(String[] args) {
        // Reproduce the r7 zero-frame calculation independently of the guard.
        double previousY = 90, currentY = 89.75;
        float partialTick = 0;
        double sampledY = previousY + partialTick * (currentY - previousY);
        check(Double.isNaN((sampledY - previousY) / partialTick), "r7 zero-frame NaN reproduced");
        for (float invalid : new float[] {0, -0f, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            check(!FirstPersonPoseSafety.canSampleJumpVelocity(invalid), "undefined jump sample blocked");
        }
        for (float valid : new float[] {Float.MIN_VALUE, .0001f, .25f, .5f, 1}) {
            check(FirstPersonPoseSafety.canSampleJumpVelocity(valid), "valid frame preserved");
        }
        for (float progress : new float[] {-100, -.01f, 0, .25f, .5f, 1, 1.01f, 100,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float safe = FirstPersonPoseSafety.refitProgress(progress, .375f);
            check(Float.isFinite(safe) && safe >= 0 && safe <= 1, "finite convex positioning weight");
            check(Float.isFinite(1 - safe) && 1 - safe >= 0, "finite nonnegative constraint weight");
        }
        check(FirstPersonPoseSafety.refitProgress(.25f, .75f) == .25f, "normal spring sample unchanged");
        check(FirstPersonPoseSafety.refitProgress(Float.NaN, .375f) == .375f, "invalid spring uses raw frame");
        check(FirstPersonPoseSafety.refitProgress(Float.NaN, Float.NaN) == 0, "fully invalid sample returns rest pose");
        System.out.println("First-person pose safety checks passed: " + checks + " assertions.");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
