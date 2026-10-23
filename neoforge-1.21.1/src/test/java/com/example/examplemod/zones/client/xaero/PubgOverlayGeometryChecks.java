package com.example.examplemod.zones.client.xaero;

import static com.example.examplemod.zones.client.xaero.XaeroOverlayGeometry.*;

/** Focused checks for the new continuous circle; the raster uses the same production geometry. */
public final class PubgOverlayGeometryChecks {
    private static int checks;
    public static void main(String[] args) {
        var view = new Viewport(160, 125, false);
        for (double radius : new double[] {4, 65, 100}) {
            var circle = circleOutline(new Point(0, 0), radius, new Projection(0, 0, 1, 1, 0), view);
            check(!circle.isEmpty(), "visible whole circle exists");
            double length = 0;
            for (int i = 0; i < circle.size(); i++) {
                var segment = circle.get(i);
                if (i > 0 && !circle.get(i - 1).end().equals(segment.start())) throw new AssertionError("continuous circle has a gap");
                length += Math.hypot(segment.end().x() - segment.start().x(), segment.end().y() - segment.start().y());
            }
            check(length > 2 * Math.PI * radius * .99, "white edge covers the full circumference");
            check(Math.hypot(circle.getFirst().start().x() - circle.getLast().end().x(),
                    circle.getFirst().start().y() - circle.getLast().end().y()) < 1e-8, "whole circle closes");
        }
        for (double radius : new double[] {10000, 60_000_000}) {
            var projection = new Projection(radius, 0, 50, .6, .8);
            var arc = circleOutline(new Point(0, 0), radius, projection, view);
            check(!arc.isEmpty() && arc.size() < 200, "large circle cost depends on viewport");
            check(arc.stream().anyMatch(segment -> clipViewport(
                    projection.project(segment.start().x(), segment.start().y()),
                    projection.project(segment.end().x(), segment.end().y()), view) != null), "zoomed nearby arc stays visible");
        }
        check(circleOutline(new Point(0, 0), 0, new Projection(0, 0, 1, 1, 0), view).isEmpty(), "invalid radius rejected");
        check(circleOutline(new Point(0, 0), 10, new Projection(0, 0, Double.NaN, 1, 0), view).isEmpty(), "invalid projection rejected");
        check(circleOutline(new Point(1000, 1000), 10, new Projection(0, 0, 1, 1, 0), view).isEmpty(), "off-screen circle skipped");
        XaeroCircleRasterReport.write();
        System.out.println("PUBG overlay geometry checks passed: " + checks);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
