package com.example.examplemod.zones.client.xaero;

import java.util.List;
import java.util.ArrayList;

/** World X/Z to Xaero's local map plane. No game classes, GL state or cached world data. */
public final class XaeroOverlayGeometry {
    private XaeroOverlayGeometry() {}
    public record Point(double x, double y) {}
    public record Segment(Point start, Point end) {}

    /** Continuous PUBG circle, tessellated only over the visible arc even at extreme map zoom. */
    public static List<Segment> circleOutline(Point center, double radius, Projection projection, Viewport view) {
        if (center == null || !Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(radius) || radius <= 0
                || !projection.valid() || view.halfWidth <= 0 || view.halfHeight <= 0) return List.of();
        double pixels = radius * projection.scale;
        double viewRadius = Math.hypot(view.halfWidth, view.halfHeight) + 4;
        double dx = projection.centerX - center.x, dz = projection.centerZ - center.y;
        double distance = Math.hypot(dx, dz) * projection.scale;
        if (!Double.isFinite(pixels) || !Double.isFinite(distance) || !Double.isFinite(viewRadius)
                || Math.abs(distance - pixels) > viewRadius) return List.of();
        double middle = Math.atan2(dz, dx), half = Math.PI;
        if (distance > 0 && distance + pixels > viewRadius) {
            double offset = distance - pixels;
            half = 2 * Math.asin(Math.sqrt(Math.clamp(
                    (viewRadius * viewRadius - offset * offset) / (4 * distance * pixels), 0, 1)));
        }
        int count = Math.max(16, Math.min(16_384, (int) Math.ceil(2 * half * pixels / 3)));
        List<Segment> result = new ArrayList<>(count);
        Point previous = new Point(center.x + Math.cos(middle - half) * radius, center.y + Math.sin(middle - half) * radius);
        for (int i = 1; i <= count; i++) {
            double angle = middle - half + 2 * half * i / count;
            Point next = new Point(center.x + Math.cos(angle) * radius, center.y + Math.sin(angle) * radius);
            result.add(new Segment(previous, next)); previous = next;
        }
        return List.copyOf(result);
    }

    /** World-anchored, screen-sized dashes. Only tessellate the visible arc, never cap a whole circle's dash count. */
    public static List<Segment> circleDashes(Point center, double radius, Projection projection, Viewport view) {
        if (center == null || !Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(radius) || radius <= 0
                || !projection.valid() || view.halfWidth <= 0 || view.halfHeight <= 0) return List.of();
        double pixels = radius * projection.scale;
        double viewRadius = Math.hypot(view.halfWidth, view.halfHeight) + 4;
        double dx = projection.centerX - center.x, dz = projection.centerZ - center.y;
        double distance = Math.hypot(dx, dz) * projection.scale;
        if (!Double.isFinite(pixels) || !Double.isFinite(distance) || !Double.isFinite(viewRadius)
                || Math.abs(distance - pixels) > viewRadius) return List.of();
        double middle = Math.atan2(dz, dx), half = Math.PI;
        if (distance > 0 && distance + pixels > viewRadius) {
            double offset = distance - pixels;
            // asin avoids acos(1 - tiny) rounding a nearby world-sized arc to zero.
            half = 2 * Math.asin(Math.sqrt(Math.clamp(
                    (viewRadius * viewRadius - offset * offset) / (4 * distance * pixels), 0, 1)));
        }
        double circumference = 2 * Math.PI * pixels;
        double count = Math.max(3, Math.floor(circumference / 14));
        double period = 2 * Math.PI / count, span = period * .60;
        long first = (long) Math.floor((middle - half) / period);
        long last = (long) Math.ceil((middle + half) / period);
        double chordAngle = 2 * Math.acos(Math.clamp(1 - Math.min(.1, previewStrokeWidth(pixels) * .2) / pixels, -1, 1));
        int pieces = Math.max(1, (int) Math.ceil(span / Math.min(3 / pixels, chordAngle > 0 ? chordAngle : 3 / pixels)));
        List<Segment> result = new ArrayList<>();
        for (long dash = first; dash < last; dash++) {
            // Clip the angular interval before allocation. Work scales with viewport pixels, not world radius.
            double start = Math.max(dash * period, middle - half);
            double end = Math.min(dash * period + span, middle + half);
            if (end <= start) continue;
            for (int piece = 0; piece < pieces; piece++) {
                double a = start + (end - start) * piece / pieces, b = start + (end - start) * (piece + 1) / pieces;
                result.add(new Segment(new Point(center.x + Math.cos(a) * radius, center.y + Math.sin(a) * radius),
                        new Point(center.x + Math.cos(b) * radius, center.y + Math.sin(b) * radius)));
            }
        }
        return List.copyOf(result);
    }

    /** Thin tiny circles as well as reducing dash count, so stroke width cannot close their gaps. */
    public static double previewStrokeWidth(double radiusPixels) { return Math.min(1.8, radiusPixels * .24); }
    public static double previewOutlineWidth(double radiusPixels) { return Math.min(3.5, radiusPixels * .42); }
    public record Interval(double min, double max) {
        public boolean present() { return Double.isFinite(min) && Double.isFinite(max) && max > min; }
        public Interval intersect(Interval other) { return new Interval(Math.max(min, other.min), Math.min(max, other.max)); }
    }
    public record Projection(double centerX, double centerZ, double scale, double ps, double pc) {
        public Point project(double x, double z) {
            double dx = (x - centerX) * scale, dz = (z - centerZ) * scale;
            return new Point(ps * dx - pc * dz, pc * dx + ps * dz);
        }
        public Point unproject(Point point) {
            return new Point(centerX + (ps * point.x + pc * point.y) / scale,
                    centerZ + (-pc * point.x + ps * point.y) / scale);
        }
        public boolean valid() { return Double.isFinite(scale) && scale > 0 && Double.isFinite(ps) && Double.isFinite(pc)
                && Math.abs(ps * ps + pc * pc - 1) < .001; }
    }
    public record Viewport(double halfWidth, double halfHeight, boolean circle) {
        public Interval row(double y) {
            if (Math.abs(y) > halfHeight) return new Interval(1, 0);
            if (!circle) return new Interval(-halfWidth, halfWidth);
            double radius = Math.min(halfWidth, halfHeight);
            if (Math.abs(y) >= radius) return new Interval(1, 0);
            double x = Math.sqrt(Math.max(0, radius * radius - y * y));
            return new Interval(-x, x);
        }
        public boolean contains(Point p) { return Math.abs(p.y) <= halfHeight && row(p.y).present()
                && p.x >= row(p.y).min && p.x <= row(p.y).max; }
        /** Both masks are convex: checking all four corners contains the entire label, including its shadow. */
        public boolean containsBox(double left, double top, double right, double bottom) {
            return right >= left && bottom >= top && contains(new Point(left, top)) && contains(new Point(right, top))
                    && contains(new Point(right, bottom)) && contains(new Point(left, bottom));
        }
    }
    public static Interval circleRow(Point center, double radius, double y) {
        double dy = y - center.y;
        if (!Double.isFinite(radius) || radius <= 0 || Math.abs(dy) >= radius) return new Interval(1, 0);
        double dx = Math.sqrt(Math.max(0, radius * radius - dy * dy));
        return new Interval(center.x - dx, center.x + dx);
    }
    /** Horizontal slice of a convex projected rectangle. */
    public static Interval polygonRow(List<Point> polygon, double y) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Point a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            if ((a.y <= y && y < b.y) || (b.y <= y && y < a.y)) {
                double x = a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y);
                min = Math.min(min, x); max = Math.max(max, x);
            }
        }
        return new Interval(min, max);
    }
    /** Signed local GUI area. Vanilla GUI's front faces have a negative area (Y points down). */
    public static double signedArea(List<Point> polygon) {
        double result = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Point a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            result += a.x * b.y - b.x * a.y;
        }
        return result * .5;
    }
    public static boolean needsGuiWindingReverse(double x1, double y1, double x2, double y2, double x3, double y3) {
        return (x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1) > 0;
    }
    /** Sutherland-Hodgman clips a convex fill to the rotated map and the minimap mask. */
    public static List<Point> clipConvex(List<Point> subject, List<Point> mask) {
        List<Point> result = subject;
        double orientation = Math.signum(signedArea(mask));
        if (orientation == 0) return List.of();
        for (int edge = 0; edge < mask.size() && !result.isEmpty(); edge++) {
            Point a = mask.get(edge), b = mask.get((edge + 1) % mask.size());
            List<Point> input = result; result = new ArrayList<>();
            Point previous = input.getLast();
            double previousSide = side(a, b, previous) * orientation;
            for (Point current : input) {
                double currentSide = side(a, b, current) * orientation;
                if ((previousSide >= 0) != (currentSide >= 0)) {
                    double fraction = previousSide / (previousSide - currentSide);
                    result.add(new Point(previous.x + (current.x - previous.x) * fraction,
                            previous.y + (current.y - previous.y) * fraction));
                }
                if (currentSide >= 0) result.add(current);
                previous = current; previousSide = currentSide;
            }
        }
        return result;
    }
    private static double side(Point a, Point b, Point point) {
        return (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x);
    }
    public static List<Point> circlePolygon(Point center, double radius, int segments) {
        List<Point> points = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            points.add(new Point(center.x + radius * Math.cos(angle), center.y + radius * Math.sin(angle)));
        }
        return points;
    }
    public static List<Point> viewportPolygon(Viewport view) {
        if (view.circle) return circlePolygon(new Point(0, 0), Math.min(view.halfWidth, view.halfHeight), 128);
        return List.of(new Point(-view.halfWidth, -view.halfHeight), new Point(view.halfWidth, -view.halfHeight),
                new Point(view.halfWidth, view.halfHeight), new Point(-view.halfWidth, view.halfHeight));
    }
    /** Return only the portions outside a circle. Inflate radius by half the stroke width before calling. */
    public static List<Point[]> outsideCircle(Point a, Point b, Point center, double radius) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double ox = a.x - center.x, oy = a.y - center.y;
        double aa = dx * dx + dy * dy;
        if (aa < 1e-20) return Math.hypot(ox, oy) >= radius ? List.<Point[]>of(new Point[]{a, b}) : List.of();
        double bb = 2 * (ox * dx + oy * dy), cc = ox * ox + oy * oy - radius * radius;
        double discriminant = bb * bb - 4 * aa * cc;
        if (discriminant <= 0) return List.<Point[]>of(new Point[]{a, b});
        double root = Math.sqrt(discriminant);
        double lo = Math.max(0, (-bb - root) / (2 * aa)), hi = Math.min(1, (-bb + root) / (2 * aa));
        if (lo >= hi) return List.<Point[]>of(new Point[]{a, b});
        List<Point[]> result = new ArrayList<>(2);
        if (lo > 0) result.add(new Point[]{a, new Point(a.x + dx * lo, a.y + dy * lo)});
        if (hi < 1) result.add(new Point[]{new Point(a.x + dx * hi, a.y + dy * hi), b});
        return result;
    }
    /** Liang-Barsky: exact line clipping before projection, including map boundaries. */
    public static Point[] clipRectangle(Point a, Point b, double minX, double minY, double maxX, double maxY) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a.x - minX, maxX - a.x, a.y - minY, maxY - a.y};
        double lo = 0, hi = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) { if (q[i] < 0) return null; }
            else if (p[i] < 0) lo = Math.max(lo, q[i] / p[i]);
            else hi = Math.min(hi, q[i] / p[i]);
            if (lo > hi) return null;
        }
        return new Point[]{new Point(a.x + dx * lo, a.y + dy * lo), new Point(a.x + dx * hi, a.y + dy * hi)};
    }
    public static Point[] clipViewport(Point a, Point b, Viewport view) {
        Point[] clipped = clipRectangle(a, b, -view.halfWidth, -view.halfHeight, view.halfWidth, view.halfHeight);
        if (clipped == null || !view.circle) return clipped;
        a = clipped[0]; b = clipped[1];
        double dx = b.x - a.x, dy = b.y - a.y, aa = dx * dx + dy * dy;
        double radius = Math.min(view.halfWidth, view.halfHeight);
        if (aa < 1e-20) return view.contains(a) ? clipped : null;
        double bb = 2 * (a.x * dx + a.y * dy), cc = a.x * a.x + a.y * a.y - radius * radius;
        double discriminant = bb * bb - 4 * aa * cc;
        if (discriminant < 0) return null;
        double root = Math.sqrt(discriminant);
        double lo = Math.max(0, (-bb - root) / (2 * aa)), hi = Math.min(1, (-bb + root) / (2 * aa));
        if (lo > hi) return null;
        return new Point[]{new Point(a.x + dx * lo, a.y + dy * lo), new Point(a.x + dx * hi, a.y + dy * hi)};
    }
}
