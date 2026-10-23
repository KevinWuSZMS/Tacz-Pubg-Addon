package com.example.examplemod.lootspawn.client;

import com.example.examplemod.localization.Texts;

/** Pure transition timing and spawn-yaw conventions, independent of Minecraft classes. */
public final class DroppedLootTransitionMath {
    public static final int HANDOFF_TICKS = 0;
    public static final int MAX_WAIT_TICKS = 10;

    private DroppedLootTransitionMath() {}

    public static boolean expired(long elapsedTicks) {
        return elapsedTicks >= MAX_WAIT_TICKS;
    }

    public static boolean canHandoff(long elapsedTicks, long readyTicks, boolean targetReady) {
        // Native ItemEntity owns the entire flight. Once the landed carrier's
        // metadata is ready, its first render segment is copied from the ghost;
        // another tick of waiting would be an artificial pause at landing.
        return targetReady && readyTicks >= HANDOFF_TICKS && !expired(elapsedTicks);
    }

    public static float spawnYaw(float yaw) {
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.dropped_loot_transition_math.nonfinite_yaw"));
        // ClientboundAddEntityPacket writes floor(yaw * 256F / 360F) as one byte.
        byte packed = (byte) (int) Math.floor(yaw * 256F / 360F);
        float decoded = packed * 360F / 256F;
        return (decoded % 360 + 360) % 360;
    }
}
