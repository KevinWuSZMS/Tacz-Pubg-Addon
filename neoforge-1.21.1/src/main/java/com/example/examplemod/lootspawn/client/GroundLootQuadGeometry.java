package com.example.examplemod.lootspawn.client;

/** Pure geometry check shared by bounds measurement and its regression tests. */
public final class GroundLootQuadGeometry {
    private GroundLootQuadGeometry() {}
    public static boolean hasArea(double[][] quad) {
        if (quad.length != 4) return false;
        for (double[] point : quad) {
            if (point.length != 3) return false;
            for (double coordinate : point) if (!Double.isFinite(coordinate)) return false;
        }
        return triangleSquaredArea(quad[0], quad[1], quad[2]) > 1e-20
                || triangleSquaredArea(quad[0], quad[2], quad[3]) > 1e-20;
    }
    private static double triangleSquaredArea(double[] a, double[] b, double[] c) {
        double x = b[0] - a[0], y = b[1] - a[1], z = b[2] - a[2];
        double u = c[0] - a[0], v = c[1] - a[1], w = c[2] - a[2];
        double crossX = y * w - z * v, crossY = z * u - x * w, crossZ = x * v - y * u;
        return crossX * crossX + crossY * crossY + crossZ * crossZ;
    }
}
