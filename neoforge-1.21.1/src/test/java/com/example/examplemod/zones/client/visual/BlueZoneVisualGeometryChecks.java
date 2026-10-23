package com.example.examplemod.zones.client.visual;

import java.util.Random;

/** Pure full-circle coverage, adaptive bounded work and non-flashing HUD geometry checks. */
public final class BlueZoneVisualGeometryChecks {
    private static int checks;

    public static void main(String[] args) {
        var box = new BlueZoneVisualGeometry.Rectangle(0, 0, 100, 100);
        var full = BlueZoneVisualGeometry.curtain(50, 50, 20, box, 50, 50, 256);
        check(!full.isEmpty(), "a nearby complete safe circle renders");
        check(full.size() <= BlueZoneVisualGeometry.MAX_SEGMENTS, "complete circles have bounded work");
        var distant = BlueZoneVisualGeometry.curtain(50, 50, 20, box, 1000, 1000, 32);
        check(!distant.isEmpty(), "a distant circle remains visible even with a short terrain distance");
        check(distant.getFirst().start().equals(distant.getLast().end()), "distant circle closes without a seam");
        check(BlueZoneVisualGeometry.curtain(50, 50, Double.NaN, box, 50, 50, 256).isEmpty(), "invalid radius emits nothing");
        check(BlueZoneVisualGeometry.curtain(50, 50, 0, box, 50, 50, 256).isEmpty(), "zero radius emits nothing");
        check(BlueZoneVisualGeometry.curtain(50, 50, 20, box, Double.POSITIVE_INFINITY, 50, 256).isEmpty(), "invalid camera emits nothing");
        var crossing = BlueZoneVisualGeometry.curtain(90, 50, 30, box, 90, 50, 256);
        check(!crossing.isEmpty(), "a circle crossing map bounds retains its complete circumference");
        check(crossing.stream().anyMatch(line -> !box.contains(line.start())), "map rectangle does not crop the visual circle");
        for (var segment : crossing) {
            check(Math.abs(Math.hypot(segment.start().x() - 90, segment.start().z() - 50) - 30) < 1e-8,
                    "complete-circle vertices stay on the circle");
        }
        var segment = new BlueZoneVisualGeometry.Segment(new BlueZoneVisualGeometry.Point(-10, 50), new BlueZoneVisualGeometry.Point(110, 50));
        var clip = BlueZoneVisualGeometry.clip(segment, box);
        check(clip != null && clip.start().x() == 0 && clip.end().x() == 100, "crossing line clips to both rectangle edges");
        check(BlueZoneVisualGeometry.clip(new BlueZoneVisualGeometry.Segment(new BlueZoneVisualGeometry.Point(-1, 0),
                new BlueZoneVisualGeometry.Point(-1, 100)), box) == null, "parallel outside line is rejected");
        var hugeMap = new BlueZoneVisualGeometry.Rectangle(-30_000_000, -30_000_000, 30_000_000, 30_000_000);
        var huge = BlueZoneVisualGeometry.curtain(-29_999_000, 0, 59_998_000, hugeMap, 29_999_000, 0, 256);
        check(!huge.isEmpty(), "world-scale radius still emits its full circumference");
        check(huge.size() <= BlueZoneVisualGeometry.MAX_SEGMENTS, "world-scale circles do not scale allocation with circumference");
        var large = BlueZoneVisualGeometry.curtain(0, 0, 4000, hugeMap, 4000, 0, 256);
        check(large.stream().anyMatch(line -> line.start().x() < -3999), "opposite side of an 8 km circle remains present");
        double nearChord = large.stream().filter(line -> Math.hypot(line.start().x() - 4000, line.start().z()) < 256)
                .mapToDouble(line -> Math.hypot(line.end().x() - line.start().x(), line.end().z() - line.start().z())).max().orElseThrow();
        double farChord = large.stream().filter(line -> line.start().x() < -3000)
                .mapToDouble(line -> Math.hypot(line.end().x() - line.start().x(), line.end().z() - line.start().z())).max().orElseThrow();
        check(nearChord < farChord / 2, "nearby circle detail is finer than its distant side");

        Random random = new Random(0xB10EC1AC1EL);
        for (int test = 0; test < 64; test++) {
            double cx = random.nextDouble() * 20_000 - 10_000;
            double cz = random.nextDouble() * 20_000 - 10_000;
            double radius = 1 + random.nextDouble() * 10_000;
            double angle = random.nextDouble() * 2 * Math.PI;
            double cameraRadius = Math.max(0, radius + random.nextDouble() * 400 - 200);
            double cameraX = cx + cameraRadius * Math.cos(angle), cameraZ = cz + cameraRadius * Math.sin(angle);
            var rectangle = new BlueZoneVisualGeometry.Rectangle(cx - radius * .8, cz - radius * .8, cx + radius * .8, cz + radius * .8);
            var lines = BlueZoneVisualGeometry.curtain(cx, cz, radius, rectangle, cameraX, cameraZ, 256);
            check(lines.size() <= BlueZoneVisualGeometry.MAX_SEGMENTS, "random maps respect hard segment limit");
            check(lines.getFirst().start().equals(lines.getLast().end()), "each random circle closes");
            BlueZoneVisualGeometry.Point previous = lines.getFirst().start();
            for (var line : lines) {
                check(previous.equals(line.start()), "adaptive subdivisions leave no gaps");
                check(Math.abs(Math.hypot(line.end().x() - cx, line.end().z() - cz) - radius) < .00001,
                        "random full-circle vertices preserve the authored radius");
                previous = line.end();
            }
        }
        check(BlueZoneVisualGeometry.edgeStrength(.5, .5) == 0, "HUD center has zero blue tint");
        check(BlueZoneVisualGeometry.edgeStrength(.14, .14) == 0, "inner 72 percent is entirely clear");
        check(BlueZoneVisualGeometry.edgeStrength(0, .5) == 1, "screen edge receives maximum gentle tint");
        check(BlueZoneVisualGeometry.edgeStrength(Double.NaN, .5) == 0, "invalid HUD point is ignored");
        check(BlueZoneVisualGeometry.ribbonFits(box, 1, 50, 99, 50, 1, 0, .48),
                "a thicker blue halo and white core remain inside the map");
        check(!BlueZoneVisualGeometry.ribbonFits(box, 0, 50, 99, 50, 1, 0, .48),
                "a bolt halo is rejected if its width crosses the map edge");
        check(!BlueZoneVisualGeometry.ribbonFits(box, 1, 50, 99, 50, 1, 0, Double.NaN),
                "invalid lightning width is rejected");
        for (int i = 0; i <= 1000; i++) {
            double current = BlueZoneVisualGeometry.lightningStrength(i / 10.0, 31);
            double next = BlueZoneVisualGeometry.lightningStrength(i / 10.0 + .1, 31);
            check(current >= .449999999 && current <= 1.000000001,
                    "stronger lightning stays visible throughout its smooth pulse");
            check(Math.abs(next - current) < .003, "lightning never jumps on or off between subframes");
        }
        System.out.println("Blue-zone visual geometry checks passed: " + checks);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
