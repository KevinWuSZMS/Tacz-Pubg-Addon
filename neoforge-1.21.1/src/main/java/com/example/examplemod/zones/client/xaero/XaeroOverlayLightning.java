package com.example.examplemod.zones.client.xaero;

import java.util.ArrayList;
import java.util.List;

import static com.example.examplemod.zones.client.xaero.XaeroOverlayGeometry.*;

/** Sparse, world-anchored electric strokes; no textures, world scan or per-block mesh. */
public final class XaeroOverlayLightning {
    private XaeroOverlayLightning() {}
    public record Segment(Point a, Point b) {}
    public static double opacity(double clock) {
        double phase = clock / 24 - Math.floor(clock / 24);
        double pulse = Math.sin(phase * Math.PI);
        return .12 + .56 * pulse * pulse;
    }

    /** Returned screen segments include clearance for the FULL stroke, not only its centerline. */
    public static List<Segment> build(double mapMinX, double mapMinZ, double mapMaxX, double mapMaxZ,
            Point safeCenter, double safeRadius, long mapId, Projection projection, Viewport viewport,
            double strokeWidth, double clock, boolean worldMap) {
        double clearance = strokeWidth / projection.scale();
        mapMinX += clearance; mapMinZ += clearance; mapMaxX -= clearance; mapMaxZ -= clearance;
        if (mapMinX >= mapMaxX || mapMinZ >= mapMaxZ) return List.of();
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (Point corner : viewportPolygon(new Viewport(viewport.halfWidth(), viewport.halfHeight(), false))) {
            Point world = projection.unproject(corner);
            minX = Math.min(minX, world.x()); minZ = Math.min(minZ, world.y());
            maxX = Math.max(maxX, world.x()); maxZ = Math.max(maxZ, world.y());
        }
        minX = Math.max(minX, mapMinX); minZ = Math.max(minZ, mapMinZ);
        maxX = Math.min(maxX, mapMaxX); maxZ = Math.min(maxZ, mapMaxZ);
        if (minX >= maxX || minZ >= maxZ) return List.of();
        double targetPixels = worldMap ? 96 : 28;
        double spacing = Math.pow(2, Math.ceil(Math.log(targetPixels / projection.scale()) / Math.log(2)));
        int maxCells = worldMap ? 192 : 48;
        // A rotated/ultrawide display cannot accidentally allocate a huge lightning field.
        while ((Math.ceil((maxX - minX) / spacing) + 2) * (Math.ceil((maxZ - minZ) / spacing) + 2) > maxCells) spacing *= 2;
        long startX = (long) Math.floor(minX / spacing), endX = (long) Math.floor(maxX / spacing);
        long startZ = (long) Math.floor(minZ / spacing), endZ = (long) Math.floor(maxZ / spacing);
        long epoch = (long) Math.floor(clock / 24);
        var inset = new Viewport(Math.max(.1, viewport.halfWidth() - strokeWidth),
                Math.max(.1, viewport.halfHeight() - strokeWidth), viewport.circle());
        List<Segment> result = new ArrayList<>();
        for (long x = startX; x <= endX; x++) for (long z = startZ; z <= endZ; z++) {
            long seed = mix(x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ mapId ^ epoch * 0x165667B19E3779F9L);
            if ((seed & 3) != 0) continue;
            double anchorX = (x + .25 + unit(seed >>> 2) * .5) * spacing;
            double anchorZ = (z + .25 + unit(seed >>> 18) * .5) * spacing;
            double angle = unit(seed >>> 34) * Math.PI * 2;
            double ux = Math.cos(angle), uz = Math.sin(angle), length = spacing * .32;
            double[] along = {-.5, -.16, -.08, .22, .5};
            double[] across = {0, .12, -.13, .06, 0};
            Point previous = null;
            for (int i = 0; i < along.length; i++) {
                Point current = new Point(anchorX + length * (along[i] * ux - across[i] * uz),
                        anchorZ + length * (along[i] * uz + across[i] * ux));
                if (previous != null) {
                    Point[] mapLine = clipRectangle(previous, current, mapMinX, mapMinZ, mapMaxX, mapMaxZ);
                    if (mapLine != null) for (Point[] blueLine : outsideCircle(mapLine[0], mapLine[1], safeCenter, safeRadius + clearance)) {
                        Point[] visible = clipViewport(projection.project(blueLine[0].x(), blueLine[0].y()),
                                projection.project(blueLine[1].x(), blueLine[1].y()), inset);
                        if (visible != null && Math.hypot(visible[1].x() - visible[0].x(), visible[1].y() - visible[0].y()) > .01)
                            result.add(new Segment(visible[0], visible[1]));
                    }
                }
                previous = current;
            }
        }
        return result;
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
    private static double unit(long value) { return (value & 65535L) / 65535.0; }
}
