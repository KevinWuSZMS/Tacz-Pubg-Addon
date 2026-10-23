package com.example.examplemod.zones.match.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Force-integrated gameplay model: quadratic drag opposes air velocity; lift is perpendicular
 * to that velocity. Coefficients absorb rho*A/(2*m) and are calibrated to gameplay endpoints,
 * not measured human aerodynamics or a claim about PUBG's closed-source implementation.
 *
 * NASA: https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/drag-equation/
 *       https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/lift-equation/
 */
public final class FreefallAerodynamics {
    public static final double FLAT_AIRSPEED = 35; // 126 km/h HUD airspeed: the complete 3-D vector.
    public static final double MAX_HORIZONTAL = FLAT_AIRSPEED; // Dissipative transient steering envelope.
    public static final double MAX_DIVE = 65; // 234 km/h vertical component and absolute airspeed cap
    public static final double GRAVITY = 32; // Vanilla 0.08 blocks/tick², at 20 ticks/s and 1 block/m.
    public static final double FLAT_COMPONENT = FLAT_AIRSPEED / Math.sqrt(2); // 1:1 maximum-input glide ratio.
    private static final double FLAT_COEFFICIENT = GRAVITY / (FLAT_AIRSPEED * FLAT_AIRSPEED);
    private static final double DIVE_DRAG = GRAVITY / (MAX_DIVE * MAX_DIVE);
    private static final int SUBSTEPS = 4;
    private static final double DT = 1.0 / (20 * SUBSTEPS);

    record Coefficients(double drag, double lift) {}
    private FreefallAerodynamics() {}

    /** Projected posture changes coefficients, rather than linearly blending velocity components. */
    static Coefficients coefficients(double pitch, double strength, boolean fast) {
        double angle = Math.toRadians(fast ? 90 : Math.clamp(pitch, 0, 90));
        double spread = Math.pow(Math.cos(angle), 2);
        double input = Math.clamp(strength, 0, 1);
        double bellyLift = FLAT_COEFFICIENT * input / Math.sqrt(2);
        // At a level view, hypot(Cd, Cl) stays g/35² for every input strength: steering trades
        // descent for distance, rather than accidentally raising the 126 km/h total-speed endpoint.
        double bellyDrag = Math.sqrt(FLAT_COEFFICIENT * FLAT_COEFFICIENT - bellyLift * bellyLift);
        return new Coefficients(DIVE_DRAG + (bellyDrag - DIVE_DRAG) * spread, bellyLift * spread);
    }

    /** Input/output remain blocks per tick; all force integration inside this method uses m/s. */
    public static Vec3 velocity(Vec3 previous, double directionX, double directionZ, double pitch, boolean fast) {
        double strength = Math.min(1, Math.hypot(directionX, directionZ));
        Vec3 heading = strength > 1e-9 ? new Vec3(directionX, 0, directionZ).normalize() : Vec3.ZERO;
        Coefficients coefficients = coefficients(pitch, strength, fast);
        Vec3 metres = cap(previous.scale(20));
        for (int step = 0; step < SUBSTEPS; step++) {
            metres = cap(metres.add(acceleration(metres, heading, coefficients).scale(DT)));
        }
        return metres.scale(1.0 / 20);
    }

    static Vec3 acceleration(Vec3 velocity, Vec3 heading, Coefficients coefficients) {
        double speed = velocity.length();
        if (speed < 1e-9) return new Vec3(0, -GRAVITY, 0);
        Vec3 direction = velocity.scale(1 / speed);
        // Project steering into the plane normal to the actual 3-D airflow. This keeps lift
        // energy-neutral, while bank/turn input redirects existing momentum smoothly.
        Vec3 normal = heading.subtract(direction.scale(heading.dot(direction)));
        if (normal.lengthSqr() > 1e-12) normal = normal.normalize();
        else {
            normal = new Vec3(0, 1, 0).subtract(direction.scale(direction.y));
            normal = normal.lengthSqr() > 1e-12 ? normal.normalize() : new Vec3(1, 0, 0);
        }
        Vec3 drag = velocity.scale(-coefficients.drag * speed);
        Vec3 lift = normal.scale(coefficients.lift * speed * speed);
        return drag.add(lift).add(0, -GRAVITY, 0);
    }

    /** Gameplay envelope only removes kinetic energy; it never supplies propulsion. */
    private static Vec3 cap(Vec3 velocity) {
        double horizontal = velocity.horizontalDistance();
        double scale = horizontal > MAX_HORIZONTAL ? MAX_HORIZONTAL / horizontal : 1;
        Vec3 bounded = new Vec3(velocity.x * scale, Math.min(0, velocity.y), velocity.z * scale);
        double speed = bounded.length();
        return speed > MAX_DIVE ? bounded.scale(MAX_DIVE / speed) : bounded;
    }
}
