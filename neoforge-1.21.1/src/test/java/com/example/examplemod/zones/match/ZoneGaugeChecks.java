package com.example.examplemod.zones.match;

import java.util.Random;

/** Pure radial geometry and conveyor checks; does not initialize Minecraft. */
public final class ZoneGaugeChecks {
    private static int checks;
    public static void main(String[] args) {
        var start = new ZoneGaugeGeometry.Ring(0, 0, 100);
        var safe = new ZoneGaugeGeometry.Ring(40, 0, 20);
        var moving = new ZoneGaugeGeometry.Ring(20, 0, 60);
        var gauge = ZoneGaugeGeometry.sample(start, moving, safe, 40, 70);
        check(Math.abs(gauge.blue() - .5) > .005, "offset radial closure is not a time percentage");
        near(gauge.safeDistance(), 50, "distance is metres, independent of normalized markers");
        check(gauge.outsideBlue() && !gauge.insideSafe(), "blue damage and white navigation have separate predicates");
        var waiting = ZoneGaugeGeometry.sample(start, null, safe, 40, 30);
        near(waiting.blue(), 0, "first waiting phase has no active blue wall");
        check(!waiting.outsideBlue() && !waiting.insideSafe(), "white-circle navigation can appear without blue damage");
        var finalCircle = ZoneGaugeGeometry.sample(safe, safe, safe, 40, 0);
        check(Double.isFinite(finalCircle.blue()) && finalCircle.insideSafe(), "equal-radius final circle avoids zero-span division");
        Random random = new Random(7331);
        for (int i = 0; i < 3000; i++) {
            double angle = random.nextDouble() * Math.PI * 2, ux = Math.cos(angle), uz = Math.sin(angle);
            double wall = ZoneGaugeGeometry.boundary(moving, safe.x(), safe.z(), ux, uz);
            var onWall = ZoneGaugeGeometry.sample(start, moving, safe, safe.x() + ux * wall, safe.z() + uz * wall);
            near(onWall.player(), onWall.blue(), "player on blue edge aligns with blue endpoint");
            var inWhite = ZoneGaugeGeometry.sample(start, moving, safe, safe.x() + ux * 10, safe.z() + uz * 10);
            near(inWhite.player(), 1, "white-circle interior clamps to its endpoint");
            check(!inWhite.outsideBlue() && inWhite.insideSafe(), "nested safe circle remains inside blue");
            var outside = ZoneGaugeGeometry.sample(start, moving, safe, safe.x() + ux * (wall + 1), safe.z() + uz * (wall + 1));
            check(outside.player() < outside.blue() && outside.outsideBlue(), "outside player lies on blue side of marker");
        }
        double frozen = ZoneGaugeGeometry.conveyorOffset(200, 16, 8);
        near(ZoneGaugeGeometry.conveyorOffset(200, 16, 8), frozen, "paused authoritative tick freezes animation");
        near(ZoneGaugeGeometry.conveyorOffset(201, 16, 8), frozen + .8, "one advancing tick moves conveyor right");
        System.out.println("ZoneGaugeChecks: " + checks + " passed; no game launched.");
    }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
