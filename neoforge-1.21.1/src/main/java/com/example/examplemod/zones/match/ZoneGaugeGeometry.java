package com.example.examplemod.zones.match;

/** Pure spatial gauge. Time, normalized radial position and metres stay separate. */
public final class ZoneGaugeGeometry {
    public record Ring(double x, double z, double radius) {}
    public record Gauge(double blue, double player, double safeDistance, boolean outsideBlue, boolean insideSafe) {}
    private ZoneGaugeGeometry() {}
    public static Gauge sample(Ring start, Ring current, Ring safe, double x, double z) {
        double dx = x - safe.x, dz = z - safe.z, distance = Math.hypot(dx, dz);
        double ux = distance > 1e-9 ? dx / distance : 1, uz = distance > 1e-9 ? dz / distance : 0;
        double outer = boundary(start, safe.x, safe.z, ux, uz);
        double wall = current == null ? outer : boundary(current, safe.x, safe.z, ux, uz);
        double span = outer - safe.radius;
        boolean outside = current != null && Math.hypot(x - current.x, z - current.z) > current.radius;
        return new Gauge(span <= 1e-9 ? 1 : clamp((outer - wall) / span),
                span <= 1e-9 ? (distance <= safe.radius ? 1 : 0) : clamp((outer - distance) / span),
                Math.max(0, distance - safe.radius), outside, distance <= safe.radius);
    }
    public static double boundary(Ring circle, double x, double z, double ux, double uz) {
        double cx = circle.x - x, cz = circle.z - z, along = cx * ux + cz * uz;
        double perpendicular = cx * cx + cz * cz - along * along;
        return Math.max(0, along + Math.sqrt(Math.max(0, circle.radius * circle.radius - perpendicular)));
    }
    public static double conveyorOffset(double ticks, double speed, double spacing) {
        return spacing <= 0 ? 0 : (ticks * speed / 20) % spacing;
    }
    private static double clamp(double value) { return Math.clamp(value, 0, 1); }
}
