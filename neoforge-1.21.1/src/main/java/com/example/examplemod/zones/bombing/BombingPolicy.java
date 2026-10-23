package com.example.examplemod.zones.bombing;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;

import java.util.random.RandomGenerator;
import java.util.function.IntPredicate;

/** Bounded scheduling and spatial rules shared by the server service and offline checks. */
public final class BombingPolicy {
    public static final int TICKS_PER_MINUTE = 1_200;
    public static final int MAX_PER_TICK = 8;
    public static final int MAX_PER_SESSION_TICK = 4;
    public static final int MAX_IN_FLIGHT_SESSION = 64;
    public static final int MAX_IN_FLIGHT_TOTAL = 256;
    public static final int MAX_LIFE_TICKS = 512;
    public static final double GRAVITY = .06;
    public static final int NO_CONTACT = Integer.MIN_VALUE;
    public static final int POINT_ATTEMPTS = 12;
    public record Point(double x, double z) {}
    private BombingPolicy() {}

    public static final class Rate {
        private int credit;
        public Rate(int phase) {
            if (phase < 0 || phase >= TICKS_PER_MINUTE) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.bombing_policy.invalid_bombing_rate_phase"));
            credit = phase;
        }
        /** Whole strikes are spent this tick, including strikes dropped by the load/budget gate. */
        public int advance(int perMinute) {
            if (perMinute < 0 || perMinute > MapZoneModel.MAX_MISSILES_PER_MINUTE)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.bombing_policy.invalid_bombing_rate"));
            credit += perMinute;
            int due = credit / TICKS_PER_MINUTE;
            credit %= TICKS_PER_MINUTE;
            return due;
        }
        public void reset() { credit = 0; }
    }

    /** Uniform rejection sampling in the circle/rectangle intersection; never expands either shape. */
    public static Point sample(MapDefinition map, Circle circle, RandomGenerator random) {
        double minX = Math.max(map.minX(), circle.centerX() - circle.radius());
        double maxX = Math.min(map.maxX(), circle.centerX() + circle.radius());
        double minZ = Math.max(map.minZ(), circle.centerZ() - circle.radius());
        double maxZ = Math.min(map.maxZ(), circle.centerZ() + circle.radius());
        if (!(maxX > minX) || !(maxZ > minZ)) return null;
        for (int attempt = 0; attempt < POINT_ATTEMPTS; attempt++) {
            double x = minX + random.nextDouble() * (maxX - minX);
            double z = minZ + random.nextDouble() * (maxZ - minZ);
            if (map.contains(x, z) && circle.contains(x, z)) return new Point(x, z);
        }
        return null;
    }

    public static double damage(double maximum, double radius, double edgeMultiplier, double distance, double exposure) {
        if (!Double.isFinite(maximum) || !Double.isFinite(radius) || !Double.isFinite(edgeMultiplier)
                || !Double.isFinite(distance) || !Double.isFinite(exposure) || maximum < 0 || radius <= 0
                || edgeMultiplier < 0 || edgeMultiplier > 1 || distance < 0 || distance > radius) return 0;
        return maximum * (1 - (1 - edgeMultiplier) * distance / radius) * Math.clamp(exposure, 0, 1);
    }

    public static boolean active(boolean finished, boolean paused, String stage) {
        return !finished && !paused && ("waiting".equals(stage) || "shrinking".equals(stage) || "final".equals(stage));
    }

    public static boolean expired(long bornTick, long currentTick, int entityAge) {
        return currentTick < bornTick || currentTick - bornTick >= MAX_LIFE_TICKS || entityAge >= MAX_LIFE_TICKS;
    }

    /** A zero initial velocity, followed by one fixed gravity step after each movement tick. */
    public static double verticalVelocity(int entityAge) { return -GRAVITY * Math.max(0, entityAge - 1); }

    public static int ceiling(int authoredHeight, int minBuildHeight, int maxBuildHeight) {
        if (maxBuildHeight <= minBuildHeight) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.bombing_policy.world_build_height_range_valid"));
        int height = authoredHeight == Integer.MAX_VALUE ? maxBuildHeight - 1 : Math.min(authoredHeight, maxBuildHeight - 1);
        return Math.max(minBuildHeight - 1, height);
    }

    /** Spawn eligibility is genuine air, independent of the separate flight pass-through list. */
    public static int highestAir(int ceiling, int minBuildHeight, IntPredicate isAir) {
        for (int y = ceiling; y >= minBuildHeight; y--) if (isAir.test(y)) return y;
        return NO_CONTACT;
    }

    /** Every crossed voxel participates, including empty collision shapes and fluids. */
    public static int firstContactY(double fromY, double toY, int minBuildHeight, int maxBuildHeight,
            IntPredicate canPassThrough) {
        if (!Double.isFinite(fromY) || !Double.isFinite(toY) || toY > fromY) return NO_CONTACT;
        int first = Math.min(maxBuildHeight - 1, (int) Math.floor(fromY));
        int last = Math.max(minBuildHeight, (int) Math.floor(Math.nextDown(toY)));
        for (int y = first; y >= last; y--) if (!canPassThrough.test(y)) return y;
        return NO_CONTACT;
    }
}
