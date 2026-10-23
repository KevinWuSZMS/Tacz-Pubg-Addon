package com.example.examplemod.visibility;

import com.example.examplemod.localization.Texts;

/** Pure distance policy: this does not load or tick any terrain. */
public final class VisibilityPolicy {
    public static final int MIN_DISTANCE = 16, MAX_DISTANCE = 1024, DEFAULT_DISTANCE = 512;
    public static final int MAX_TARGETS = 128, MAX_TARGET_NBT_BYTES = 1024;
    public record Settings(int players, int targets) {
        public Settings {
            requireDistance(players); requireDistance(targets);
        }
        public static Settings defaults() { return new Settings(DEFAULT_DISTANCE, DEFAULT_DISTANCE); }
    }
    private VisibilityPolicy() {}
    public static void requireDistance(int distance) {
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.visibility_policy.distances_blocks"));
    }
    public static boolean inRange(double distanceSquared, int distance) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= (double) distance * distance;
    }
    /** A carrier inherits the greatest range of the managed targets in its passenger tree. */
    public static int distanceForKinds(Settings settings, boolean carriesPlayer, boolean carriesTarget) {
        return Math.max(carriesPlayer ? settings.players() : 0, carriesTarget ? settings.targets() : 0);
    }
    public static double originalRemotePlayerDistance(int chunks, double entityScale, boolean crouching) {
        double size = (0.6 + (crouching ? 1.5 : 1.8) + 0.6) / 3.0;
        return size * 10 * 64 * Math.clamp(chunks / 8.0, 1, 2.5) * entityScale;
    }
}
