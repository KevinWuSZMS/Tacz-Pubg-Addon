package com.example.examplemod.zones.client.xaero;

import java.util.List;
import java.util.Random;
import static com.example.examplemod.zones.client.xaero.XaeroOverlayGeometry.*;

/** Offline checks: no Minecraft, graphics driver, Xaero bootstrap or mod loading. */
public final class XaeroOverlayChecks {
    private static int checks;
    public static void main(String[] args) {
        var northUp = new Projection(100, 200, 2, 1, 0);
        point(northUp.project(101, 200), 2, 0, "east on north-up map");
        point(northUp.project(100, 201), 0, 2, "south on north-up map");
        var rotated = new Projection(100, 200, 2, 0, 1);
        point(rotated.project(101, 200), 0, 2, "east rotates with map");
        point(rotated.project(100, 201), -2, 0, "south rotates with map");
        check(!new Projection(0, 0, 0, 1, 0).valid(), "reject zero zoom");
        check(!new Projection(0, 0, 1, .5, .5).valid(), "reject invalid rotation");
        var nextCircle = circleDashes(new Point(10, -20), 100, new Projection(10, -20, 1, 1, 0), new Viewport(200, 200, false));
        check(!nextCircle.isEmpty(), "next-circle preview emits visible dashes");
        double dashLength = 0;
        for (var dash : nextCircle) {
            check(Math.abs(Math.hypot(dash.start().x() - 10, dash.start().y() + 20) - 100) < 1e-8
                    && Math.abs(Math.hypot(dash.end().x() - 10, dash.end().y() + 20) - 100) < 1e-8,
                    "preview dashes follow the announced circle exactly");
            dashLength += Math.hypot(dash.end().x() - dash.start().x(), dash.end().y() - dash.start().y());
        }
        check(dashLength > 2 * Math.PI * 100 * .58 && dashLength < 2 * Math.PI * 100 * .61,
                "preview preserves substantial visible gaps instead of drawing a solid circle");
        check(circleDashes(new Point(0, 0), 60_000_000, new Projection(60_000_000, 0, 50, 1, 0),
                new Viewport(1920, 1080, false)).size() <= 2048, "world-sized preview has bounded geometry at maximum zoom");
        check(circleDashes(new Point(0, 0), 0, northUp, new Viewport(100, 100, false)).isEmpty()
                && circleDashes(new Point(0, 0), 1, new Projection(0, 0, Double.NaN, 1, 0),
                new Viewport(100, 100, false)).isEmpty(), "invalid previews emit nothing");
        visibleCircleDashes();
        check(!circleRow(new Point(0, 0), 10, 10).present(), "circle tangent has no fill");
        interval(circleRow(new Point(0, 0), 5, 3), -4, 4, "analytic circle slice");
        var square = List.of(new Point(-10, -10), new Point(10, -10), new Point(10, 10), new Point(-10, 10));
        interval(polygonRow(square, 0), -10, 10, "rectangular map slice");
        check(!polygonRow(square, 11).present(), "nothing outside map");
        var circle = new Viewport(10, 10, true);
        check(circle.containsBox(-3, -3, 3, 3), "whole central label fits round minimap");
        check(!circle.containsBox(5, 5, 9, 9), "label corner beyond circle is hidden although its anchor is inside");
        check(!circle.containsBox(1, 0, 13, 10), "two-digit label and shadow cannot cross round mask");
        check(new Viewport(10, 10, false).containsBox(-10, -10, 10, 10), "square mask contains its full bounds");
        check(!new Viewport(10, 10, false).containsBox(1, 1, 11, 9), "long world-map label cannot escape viewport");
        var clipped = clipViewport(new Point(-20, 6), new Point(20, 6), circle);
        point(clipped[0], -8, 6, "round minimap left edge");
        point(clipped[1], 8, 6, "round minimap right edge");
        check(clipViewport(new Point(-20, 11), new Point(20, 11), circle) == null, "line beyond circle hidden");
        check(clipRectangle(new Point(-2, -1), new Point(-2, 2), 0, 0, 1, 1) == null, "parallel external segment hidden");
        var crossing = clipRectangle(new Point(-5, .5), new Point(5, .5), 0, 0, 1, 1);
        point(crossing[0], 0, .5, "map boundary line entry"); point(crossing[1], 1, .5, "map boundary line exit");
        // Regression: these exact old strip vertices are back faces under RenderType.GUI's default CULL.
        check(needsGuiWindingReverse(-4, -3, 4, -3, 4, 3), "old rectangular fill requires winding repair");
        check(!needsGuiWindingReverse(4, 3, 4, -3, -4, -3), "vanilla GuiGraphics.fill winding is already a front face");
        check(!needsGuiWindingReverse(-4, 1, 4, 1, 4, -1), "existing line strokes remain front faces");
        var filledCircle = circlePolygon(new Point(0, 0), 3, 128);
        check(Math.abs(signedArea(filledCircle) - Math.PI * 9) < .02, "complete red disk covers its interior");
        var clippedDisk = clipConvex(filledCircle, List.of(new Point(0, -10), new Point(10, -10), new Point(10, 10), new Point(0, 10)));
        check(Math.abs(signedArea(clippedDisk) - Math.PI * 4.5) < .02, "red disk clipped at map border retains half its area");
        check(clipConvex(filledCircle, List.of(new Point(20, 20), new Point(21, 20), new Point(21, 21), new Point(20, 21))).isEmpty(),
                "red disk completely beyond map has no fill");
        var tinyDisk = circlePolygon(new Point(0, 0), .2, 64);
        check(signedArea(tinyDisk) > .12, "red disk smaller than raster row still has fill");
        var halves = outsideCircle(new Point(-10, 0), new Point(10, 0), new Point(0, 0), 3);
        check(halves.size() == 2, "lightning is split around safe circle");
        point(halves.get(0)[1], -3, 0, "lightning safe-circle left contact");
        point(halves.get(1)[0], 3, 0, "lightning safe-circle right contact");
        check(outsideCircle(new Point(-1, 0), new Point(1, 0), new Point(0, 0), 3).isEmpty(), "lightning inside safe circle removed");
        check(outsideCircle(new Point(-10, 4), new Point(10, 4), new Point(0, 0), 3).size() == 1, "external lightning remains whole");
        check(XaeroOverlayLightning.opacity(0) >= .1 && XaeroOverlayLightning.opacity(12) > .6,
                "lightning softly pulses without a fully opaque flash");
        var random = new Random(2166147);
        for (int i = 0; i < 10000; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double zoom = .05 + random.nextDouble() * 20;
            var projection = new Projection(29_000_000, -29_000_000, zoom, Math.sin(angle), Math.cos(angle));
            double dx = random.nextDouble() * 200 - 100, dz = random.nextDouble() * 200 - 100;
            Point projected = projection.project(29_000_000 + dx, -29_000_000 + dz);
            check(Math.abs(Math.hypot(projected.x(), projected.y()) - Math.hypot(dx, dz) * zoom) < 1e-6,
                    "projection preserves distance at large world coordinates");
            point(projection.unproject(projected), 29_000_000 + dx, -29_000_000 + dz, "inverse projection preserves map position");
            Point a = new Point(random.nextDouble() * 80 - 40, random.nextDouble() * 80 - 40);
            Point b = new Point(random.nextDouble() * 80 - 40, random.nextDouble() * 80 - 40);
            Point[] result = clipViewport(a, b, circle);
            if (result != null) {
                for (Point endpoint : result) check(Math.hypot(endpoint.x(), endpoint.y()) <= 10 + 1e-7, "no line endpoint outside round mask");
                double lineX = b.x() - a.x(), lineY = b.y() - a.y();
                for (Point endpoint : result) check(Math.abs((endpoint.x() - a.x()) * lineY - (endpoint.y() - a.y()) * lineX) < 1e-6,
                        "clipping never moves marker or border off original line");
            }
            double y = random.nextDouble() * 20 - 10;
            Interval row = circle.row(y);
            check(row.present() && Math.abs(row.min() * row.min() + y * y - 100) < 1e-7, "round mask row matches boundary");
        }
        int emitted = 0;
        for (int i = 0; i < 300; i++) {
            double angle = random.nextDouble() * Math.PI * 2, zoom = .01 + random.nextDouble() * 12;
            var projection = new Projection(random.nextDouble() * 200 - 100, random.nextDouble() * 200 - 100,
                    zoom, Math.cos(angle), Math.sin(angle));
            var view = new Viewport(120, 80, i % 2 == 0);
            Point safeCenter = new Point(3, 7);
            var strokes = XaeroOverlayLightning.build(-300, -240, 300, 240, safeCenter, 25, 7,
                    projection, view, 1, i * .3, false);
            check(strokes.size() <= 48 * 8, "lightning complexity has a viewport-dependent cap");
            emitted += strokes.size();
            for (var stroke : strokes) for (int j = 0; j <= 4; j++) {
                Point screen = new Point(stroke.a().x() + (stroke.b().x() - stroke.a().x()) * j / 4,
                        stroke.a().y() + (stroke.b().y() - stroke.a().y()) * j / 4);
                Point world = projection.unproject(screen);
                check(view.contains(screen), "lightning stays inside rectangular or round minimap");
                check(world.x() >= -300 && world.x() <= 300 && world.y() >= -240 && world.y() <= 240,
                        "lightning stays inside map while panning and rotating");
                check(Math.hypot(world.x() - safeCenter.x(), world.y() - safeCenter.y()) >= 25 - 1e-8,
                        "lightning never crosses safe circle");
            }
            for (var stroke : strokes) {
                double dx = stroke.b().x() - stroke.a().x(), dy = stroke.b().y() - stroke.a().y();
                double length = Math.hypot(dx, dy), nx = -dy / length * .5, ny = dx / length * .5;
                for (Point end : List.of(stroke.a(), stroke.b())) for (int sign : new int[]{-1, 1}) {
                    Point corner = new Point(end.x() + nx * sign, end.y() + ny * sign);
                    Point world = projection.unproject(corner);
                    check(view.contains(corner), "full lightning stroke stays within minimap mask");
                    check(world.x() >= -300 && world.x() <= 300 && world.y() >= -240 && world.y() <= 240,
                            "full lightning stroke stays within map rectangle");
                    check(Math.hypot(world.x() - safeCenter.x(), world.y() - safeCenter.y()) >= 25 - 1e-8,
                            "full lightning stroke never touches safe side");
                }
            }
            var mapPolygon = List.of(projection.project(-300, -240), projection.project(300, -240),
                    projection.project(300, 240), projection.project(-300, 240));
            var red = clipConvex(circlePolygon(projection.project(230, 200), 100 * zoom, 96), mapPolygon);
            red = clipConvex(red, viewportPolygon(view));
            for (Point point : red) {
                check(Math.abs(point.x()) <= 120 + 1e-8 && Math.abs(point.y()) <= 80 + 1e-8, "red fill obeys rectangular viewport");
                if (view.circle()) check(Math.hypot(point.x(), point.y()) <= 80 + 1e-8, "red fill obeys round viewport");
                Point world = projection.unproject(point);
                check(world.x() >= -300 - 1e-8 && world.x() <= 300 + 1e-8 && world.y() >= -240 - 1e-8 && world.y() <= 240 + 1e-8,
                        "red fill obeys rotated map rectangle");
                check(Math.hypot(world.x() - 230, world.y() - 200) <= 100 + 1e-8, "red fill stays within bombing circle");
            }
        }
        check(emitted > 100, "visible blue area actually emits sparse lightning");
        var huge = XaeroOverlayLightning.build(-29_999_984, -29_999_984, 29_999_984, 29_999_984, new Point(0, 0), 10,
                1, new Projection(0, 0, .0001, 1, 0), new Viewport(3840, 1080, false), 1, 8, true);
        check(huge.size() <= 192 * 8, "world-sized map and ultrawide view still have bounded lightning");
        XaeroCircleRasterReport.write();
        System.out.println("Xaero overlay geometry checks: " + checks + " passed");
    }
    private static void visibleCircleDashes() {
        // The previous 256-dash cap left an entire minimap in one enormous gap at high zoom.
        for (double radius : new double[]{100, 10_000, 1_000_000, 60_000_000}) {
            for (double zoom : new double[]{.125, 1, 8, 50}) for (int angle = 0; angle < 16; angle++) {
                double theta = angle * Math.PI / 8 + .007;
                var center = new Point(-1234, 5678);
                var projection = new Projection(center.x() + Math.cos(theta) * radius,
                        center.y() + Math.sin(theta) * radius, zoom, Math.sin(theta), Math.cos(theta));
                var view = new Viewport(64, 64, angle % 2 == 0);
                var segments = circleDashes(center, radius, projection, view);
                int visible = 0;
                check(segments.size() < 256, "visible-arc allocation is bounded by the minimap, even at maximum world radius");
                for (var segment : segments) {
                    var a = projection.project(segment.start().x(), segment.start().y());
                    var b = projection.project(segment.end().x(), segment.end().y());
                    check(Math.hypot(a.x() - b.x(), a.y() - b.y()) <= 3.01,
                            "zoom cannot turn a short dash into a viewport-wide straight segment");
                    if (clipViewport(a, b, view) != null) visible++;
                }
                check(visible >= 4, "nearby announced circle remains visible at every pan, rotation and zoom");
            }
        }
        // Sample the mathematical circumference independently of the generated dash count/phase.
        for (double pixels : new double[]{2, 4, 6, 10, 30, 65, 150, 500}) {
            var projection = new Projection(0, 0, 1, 1, 0);
            var segments = circleDashes(new Point(0, 0), pixels, projection, new Viewport(600, 600, false));
            int white = 0, outline = 0;
            for (int i = 0; i < 3600; i++) {
                double angle = 2 * Math.PI * (i + .37) / 3600;
                var sample = new Point(Math.cos(angle) * pixels, Math.sin(angle) * pixels);
                double nearest = segments.stream().mapToDouble(line -> distanceToSegment(sample, line)).min().orElse(Double.POSITIVE_INFINITY);
                if (nearest <= previewStrokeWidth(pixels) / 2) white++;
                if (nearest <= previewOutlineWidth(pixels) / 2) outline++;
            }
            check(white > 1200 && white < 2800, "white circumference coverage has both visible dashes and open gaps at radius " + pixels);
            check(outline < 3000, "dark outlines cannot close every gap on tiny announced circles");
        }
        check(circleDashes(new Point(0, 0), 1000, new Projection(0, 0, 1, 1, 0), new Viewport(64, 64, true)).isEmpty(),
                "fully off-screen circle emits no segments");
    }

    private static double distanceToSegment(Point p, Segment line) {
        double dx = line.end().x() - line.start().x(), dy = line.end().y() - line.start().y();
        double t = ((p.x() - line.start().x()) * dx + (p.y() - line.start().y()) * dy) / (dx * dx + dy * dy);
        if (t < 0 || t > 1) return Double.POSITIVE_INFINITY; // Match the renderer's butt-ended stroke quads.
        return Math.hypot(p.x() - line.start().x() - dx * t, p.y() - line.start().y() - dy * t);
    }
    private static void interval(Interval actual, double min, double max, String label) {
        check(actual.present() && Math.abs(actual.min() - min) < 1e-8 && Math.abs(actual.max() - max) < 1e-8, label);
    }
    private static void point(Point actual, double x, double y, String label) {
        check(actual != null && Math.abs(actual.x() - x) < 1e-8 && Math.abs(actual.y() - y) < 1e-8, label);
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
