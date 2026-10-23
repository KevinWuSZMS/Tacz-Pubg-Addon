package com.example.examplemod.zones.client.visual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded, Minecraft-independent geometry for a complete cylindrical blue-zone curtain. */
public final class BlueZoneVisualGeometry {
    public static final int MAX_SEGMENTS = 384;
    public static final double MAX_DETAIL_DISTANCE = 256;
    private static final int BASE_SEGMENTS = 64;
    private static final double TWO_PI = 2 * Math.PI;

    private BlueZoneVisualGeometry() {}

    public record Point(double x, double z) {}
    public record Segment(Point start, Point end) {}
    public record Rectangle(double minX, double minZ, double maxX, double maxZ) {
        public boolean contains(Point p) {
            return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ;
        }
    }

    private record Arc(double start, double end, double priority) {}

    /**
     * Always covers the full circle, including parts outside the authored map rectangle.
     * Distance only controls tessellation: nearby chords stay fine while distant arcs stay cheap.
     * A priority queue gives the nearest visible curvature the fixed remaining vertex budget.
     */
    public static List<Segment> curtain(double centerX, double centerZ, double radius,
            Rectangle map, double cameraX, double cameraZ, double requestedDistance) {
        if (!valid(map) || !finite(centerX, centerZ, radius, cameraX, cameraZ, requestedDistance)
                || radius <= 0 || requestedDistance <= 0) return List.of();
        double detailDistance = Math.min(MAX_DETAIL_DISTANCE, requestedDistance);
        double dx = cameraX - centerX, dz = cameraZ - centerZ, d = Math.hypot(dx, dz);
        double cameraAngle = Math.atan2(dz, dx);
        if (cameraAngle < 0) cameraAngle += TWO_PI;
        PriorityQueue<Arc> arcs = new PriorityQueue<>(Comparator.comparingDouble(Arc::priority).reversed());
        for (int i = 0; i < BASE_SEGMENTS; i++) {
            double start = TWO_PI * i / BASE_SEGMENTS, end = TWO_PI * (i + 1) / BASE_SEGMENTS;
            arcs.add(arc(start, end, radius, d, cameraAngle, detailDistance));
        }
        while (arcs.size() < MAX_SEGMENTS && arcs.peek().priority() > 1) {
            Arc old = arcs.remove();
            double middle = (old.start + old.end) / 2;
            arcs.add(arc(old.start, middle, radius, d, cameraAngle, detailDistance));
            arcs.add(arc(middle, old.end, radius, d, cameraAngle, detailDistance));
        }
        List<Arc> ordered = new ArrayList<>(arcs);
        ordered.sort(Comparator.comparingDouble(Arc::start));
        List<Segment> result = new ArrayList<>(ordered.size());
        Point first = onCircle(centerX, centerZ, radius, 0), previous = first;
        for (int i = 0; i < ordered.size(); i++) {
            Point next = i == ordered.size() - 1 ? first : onCircle(centerX, centerZ, radius, ordered.get(i).end);
            result.add(new Segment(previous, next));
            previous = next;
        }
        return List.copyOf(result);
    }

    private static Arc arc(double start, double end, double radius, double cameraRadius,
            double cameraAngle, double detailDistance) {
        double angleDistance = cameraAngle >= start && cameraAngle <= end ? 0
                : Math.min(angularDistance(start, cameraAngle), angularDistance(end, cameraAngle));
        // Half-angle form avoids subtracting almost equal world-sized squared radii.
        double sine = Math.sin(angleDistance / 2);
        double distance = Math.hypot(cameraRadius - radius, 2 * Math.sqrt(cameraRadius * radius) * sine);
        double half = (end - start) / 2;
        double sagitta = 2 * radius * Math.pow(Math.sin(half / 2), 2);
        double allowedError = .15 + Math.max(0, distance - detailDistance) * .0025;
        return new Arc(start, end, sagitta / allowedError);
    }

    private static double angularDistance(double first, double second) {
        double difference = Math.abs(first - second);
        return Math.min(difference, TWO_PI - difference);
    }

    /** Optional Liang-Barsky utility for map-bound callers; full-circle curtains do not use it. */
    public static Segment clip(Segment line, Rectangle rectangle) {
        if (line == null || !valid(rectangle)
                || !finite(line.start.x, line.start.z, line.end.x, line.end.z)) return null;
        double dx = line.end.x - line.start.x, dz = line.end.z - line.start.z;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {line.start.x - rectangle.minX, rectangle.maxX - line.start.x,
                line.start.z - rectangle.minZ, rectangle.maxZ - line.start.z};
        double lower = 0, upper = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) return null;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) lower = Math.max(lower, t);
                else upper = Math.min(upper, t);
                if (lower > upper) return null;
            }
        }
        return new Segment(new Point(clamp(line.start.x + lower * dx, rectangle.minX, rectangle.maxX),
                clamp(line.start.z + lower * dz, rectangle.minZ, rectangle.maxZ)),
                new Point(clamp(line.start.x + upper * dx, rectangle.minX, rectangle.maxX),
                        clamp(line.start.z + upper * dz, rectangle.minZ, rectangle.maxZ)));
    }

    /** Positive only in the outer 14%; the middle remains completely clear. */
    public static double edgeStrength(double xFraction, double yFraction) {
        if (!finite(xFraction, yFraction) || xFraction < 0 || xFraction > 1 || yFraction < 0 || yFraction > 1) return 0;
        double distance = Math.min(Math.min(xFraction, 1 - xFraction), Math.min(yFraction, 1 - yFraction));
        double strength = Math.max(0, 1 - distance / .14);
        return strength * strength;
    }

    /** A continuous five-second pulse, never an on/off lightning flash. */
    public static double lightningStrength(double gameTicks, long seed) {
        if (!Double.isFinite(gameTicks)) return 0;
        double phase = ((seed & 1023) / 1024.0) * TWO_PI;
        return .725 + .275 * Math.sin(gameTicks * TWO_PI / 100 + phase);
    }

    /** Includes the lightning halo, so a thicker bolt never spills outside the map rectangle. */
    public static boolean ribbonFits(Rectangle map, double ax, double az, double bx, double bz,
            double tx, double tz, double halfWidth) {
        return valid(map) && finite(ax, az, bx, bz, tx, tz, halfWidth) && halfWidth >= 0
                && map.contains(new Point(ax - tx * halfWidth, az - tz * halfWidth))
                && map.contains(new Point(ax + tx * halfWidth, az + tz * halfWidth))
                && map.contains(new Point(bx - tx * halfWidth, bz - tz * halfWidth))
                && map.contains(new Point(bx + tx * halfWidth, bz + tz * halfWidth));
    }

    private static Point onCircle(double x, double z, double radius, double angle) {
        return new Point(x + radius * Math.cos(angle), z + radius * Math.sin(angle));
    }

    private static boolean valid(Rectangle map) {
        return map != null && finite(map.minX, map.minZ, map.maxX, map.maxZ)
                && map.maxX > map.minX && map.maxZ > map.minZ;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
