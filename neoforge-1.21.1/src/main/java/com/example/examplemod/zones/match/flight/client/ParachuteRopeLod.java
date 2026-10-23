package com.example.examplemod.zones.match.flight.client;

/** Distance is measured to the interpolated wearer, in Minecraft blocks. */
final class ParachuteRopeLod {
    record Bounds(double radius, double minY, double maxY) {}
    static final int NEAR_DISTANCE = 32;
    static final int MEDIUM_DISTANCE = 64;
    static final int FAR_DISTANCE = 128;
    static final int HIDDEN = 3;

    private ParachuteRopeLod() {}

    static Bounds bounds(float[] canopy, float[] nearRopes) {
        double radius = 0, minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        // Resource-load work only: the complete near mesh bounds every smaller LOD.
        for (int part = 0; part < 2; part++) {
            float[] data = part == 0 ? canopy : nearRopes;
            if (data == null) continue;
            for (int i = 0; i < data.length; i += 8) {
                radius = Math.max(radius, Math.hypot(data[i], data[i + 2]));
                minY = Math.min(minY, data[i + 1]);
                maxY = Math.max(maxY, data[i + 1]);
            }
        }
        return new Bounds(radius, minY, maxY);
    }

    static int select(boolean localPlayer, double distanceSquared) {
        // The wearer can look up at their own lines in either camera mode.
        if (localPlayer) return 0;
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0) return HIDDEN;
        if (distanceSquared <= NEAR_DISTANCE * NEAR_DISTANCE) return 0;
        if (distanceSquared <= MEDIUM_DISTANCE * MEDIUM_DISTANCE) return 1;
        if (distanceSquared <= FAR_DISTANCE * FAR_DISTANCE) return 2;
        return HIDDEN;
    }
}
