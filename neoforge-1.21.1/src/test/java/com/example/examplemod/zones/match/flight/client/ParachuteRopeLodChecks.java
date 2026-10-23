package com.example.examplemod.zones.match.flight.client;

/** Runs without initializing Minecraft or its registry. */
public final class ParachuteRopeLodChecks {
    public static void main(String[] args) {
        check(0, false, 0);
        check(0, false, 32 * 32);
        check(1, false, Math.nextUp(32.0 * 32));
        check(1, false, 64 * 64);
        check(2, false, Math.nextUp(64.0 * 64));
        check(2, false, 128 * 128);
        check(3, false, Math.nextUp(128.0 * 128));
        check(3, false, Double.NaN);
        check(3, false, Double.POSITIVE_INFINITY);
        check(3, false, -1);
        for (double distance : new double[] {0, 32, 48, 64, 96, 128, 256, 1024}) {
            check(0, true, distance * distance);
        }
        float[] canopy = {3, 3, 0, 0, 0, 0, 1, 0, -3, 5, 0, 0, 0, 0, 1, 0};
        float[] ropes = {0.2f, 0.3f, 0.1f, 0, 0, 0, 1, 0, 3.01f, 3, 0, 0, 0, 0, 1, 0};
        var bounds = ParachuteRopeLod.bounds(canopy, ropes);
        for (float[] part : new float[][] {canopy, ropes}) {
            for (int i = 0; i < part.length; i += 8) {
                if (bounds.minY() > part[i + 1] || bounds.maxY() < part[i + 1]
                        || bounds.radius() < Math.hypot(part[i], part[i + 2]))
                    throw new AssertionError("Canopy culling bounds exclude a rope endpoint");
            }
        }
        var detailed = ParachuteRopeLod.bounds(canopy, null);
        if (detailed.minY() != 3 || detailed.maxY() != 5 || detailed.radius() != 3)
            throw new AssertionError("Detailed canopy bounds changed");
        System.out.println("PASS: rope LOD boundaries, local visibility and culling bounds including rope endpoints");
    }

    private static void check(int expected, boolean local, double squaredDistance) {
        int actual = ParachuteRopeLod.select(local, squaredDistance);
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual
                + " for local=" + local + ", distanceSquared=" + squaredDistance);
    }
}
