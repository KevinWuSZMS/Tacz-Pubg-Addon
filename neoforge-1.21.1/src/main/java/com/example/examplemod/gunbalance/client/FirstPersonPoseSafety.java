package com.example.examplemod.gunbalance.client;

/** Guards legal render-frame boundary values without changing authored animation timing. */
public final class FirstPersonPoseSafety {
    private FirstPersonPoseSafety() {}

    public static boolean canSampleJumpVelocity(float partialTick) {
        // TaCZ divides by this value. Zero is legal at the start of a Minecraft tick.
        return Float.isFinite(partialTick) && partialTick > 0;
    }

    public static float refitProgress(float smoothed, float raw) {
        float value = Float.isFinite(smoothed) ? smoothed : raw;
        if (!Float.isFinite(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }
}
