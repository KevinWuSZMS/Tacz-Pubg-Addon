package com.example.examplemod.zones.match.flight;

import net.minecraft.world.phys.Vec3;

/** Focused input and descent regressions; no world or client is started. */
public final class FlightMotionChecks {
    private static int checks;
    public static void main(String[] args) {
        freefallForcesAndEndpoints();
        var dive = control(2, 1, 0, 0, 90, true, false);
        Vec3 diving = settle(Vec3.ZERO, dive);
        check(close(diving.length() * 72, 234) && close(diving.x, 0) && close(diving.z, 0),
                "sprint dive reaches 234 km/h without lateral drift");
        Vec3 overspeed = FlightMotion.velocity(new Vec3(20, -20, 20), control(2, 1, 1, 30, 45, false, false));
        check(overspeed.length() <= 65.0 / 20.0 + 1e-9, "external momentum cannot exceed descent speed limit");
        var forwardControl = control(3, 1, 0, 0, 0, false, false);
        Vec3 forward = settle(Vec3.ZERO, forwardControl);
        Vec3 diagonal = settle(Vec3.ZERO, control(3, 1, 1, 0, 0, false, false));
        check(close(forward.horizontalDistance(), diagonal.horizontalDistance())
                        && close(forward.horizontalDistance() * 20, 10),
                "diagonal canopy input does not multiply horizontal speed");
        Vec3 glide = settle(Vec3.ZERO, control(3, 1, 0, 0, 0, false, true));
        check(close(-glide.y * 20, 3) && glide.horizontalDistance() / -glide.y
                        > forward.horizontalDistance() / -forward.y,
                "Ctrl canopy glide travels farther for each metre lost");
        Vec3 opened = diving;
        for (int tick = 0; tick < 20; tick++) opened = FlightMotion.velocity(opened, forwardControl);
        check(-opened.y * 20 < 6.1 && opened.y > diving.y,
                "canopy opening brakes a maximum-speed dive within one second");
        Vec3 turning = FlightMotion.velocity(forward, control(3, 1, 0, 90, 0, false, false));
        check(turning.x < 0 && turning.z > 0, "turning retains momentum instead of snapping to a new heading");
        Vec3 turned = settle(turning, control(3, 1, 0, 90, 0, false, false));
        check(close(turned.x, -0.5) && close(turned.z, 0), "turn settles toward the new heading");
        Vec3 stopped = settle(forward, control(3, 0, 0, 0, 0, false, false));
        check(close(stopped.horizontalDistance(), 0) && close(-stopped.y * 20, 6),
                "releasing movement brakes while retaining neutral canopy descent");
        Vec3 fast = settle(Vec3.ZERO, control(3, 1, 0, 0, 0, true, false));
        check(close(fast.horizontalDistance() * 20, 12) && close(-fast.y * 20, 15),
                "Shift canopy descent remains steerable and descends faster");
        System.out.println("FlightMotionChecks: " + checks + " focused checks passed; no game launched.");
    }
    private static void freefallForcesAndEndpoints() {
        Vec3 flat = settle(Vec3.ZERO, control(2, 1, 0, 0, 0, false, false));
        check(close(flat.length() * 72, 126), "Level-view HUD speed is the full 126 km/h vector, not its horizontal component");
        check(close(flat.horizontalDistance() * 20, FreefallAerodynamics.FLAT_COMPONENT)
                        && close(-flat.y * 20, FreefallAerodynamics.FLAT_COMPONENT),
                "Level flight splits speed into a physically descending 1:1 glide, rather than adding 45 m/s below 126 km/h horizontal");
        Vec3 idle = settle(Vec3.ZERO, control(2, 0, 0, 0, 0, false, false));
        check(close(idle.length() * 72, 126) && close(idle.horizontalDistance(), 0),
                "Idle belly posture has the same 126 km/h terminal HUD speed without supplying horizontal propulsion");
        Vec3 half = settle(Vec3.ZERO, control(2, .5f, 0, 0, 0, false, false));
        check(close(half.length() * 72, 126) && half.horizontalDistance() < flat.horizontalDistance(),
                "Analog steering trades horizontal distance for descent without changing the flat-pose total-speed calibration");
        Vec3 diagonal = settle(Vec3.ZERO, control(2, 1, 1, 0, 0, false, false));
        check(close(diagonal.length(), flat.length()) && close(diagonal.horizontalDistance(), flat.horizontalDistance()),
                "Diagonal freefall input does not multiply lift or horizontal speed");
        Vec3 rotated = settle(Vec3.ZERO, control(2, 1, 0, 90, 0, false, false));
        check(close(rotated.x, -flat.z) && close(rotated.z, flat.x) && close(rotated.y, flat.y),
                "Yaw only rotates the 3-D velocity around the vertical axis");
        double last = 0;
        for (int pitch = 0; pitch <= 90; pitch += 5) {
            Vec3 value = settle(Vec3.ZERO, control(2, 1, 0, 0, pitch, false, false));
            double speed = value.length() * 20;
            check(speed + 1e-7 >= last && speed <= 65 + 1e-8 && value.y <= 0,
                    "Posture changes produce bounded, monotonic terminal descent at pitch " + pitch);
            last = speed;
        }
        Vec3 middle = settle(Vec3.ZERO, control(2, 1, 0, 0, 45, false, false));
        check(Math.abs(middle.length() * 20 - 50) > 2,
                "45-degree speed is not the arithmetic midpoint of 35 and 65 m/s");
        var coefficients = FreefallAerodynamics.coefficients(45, 1, false);
        double resultant = Math.hypot(coefficients.drag(), coefficients.lift());
        double terminal = Math.sqrt(FreefallAerodynamics.GRAVITY / resultant);
        check(close(middle.length() * 20, terminal)
                        && close(middle.horizontalDistance() * 20, terminal * coefficients.lift() / resultant)
                        && close(-middle.y * 20, terminal * coefficients.drag() / resultant),
                "Integrated 3-D equilibrium agrees with the independent lift/drag force-balance solution");
        Vec3 motion = new Vec3(11, -42, 8), heading = new Vec3(-.6, 0, .8);
        Vec3 aerodynamic = FreefallAerodynamics.acceleration(motion, heading, coefficients)
                .add(0, FreefallAerodynamics.GRAVITY, 0);
        check(Math.abs(aerodynamic.dot(motion) + coefficients.drag() * Math.pow(motion.length(), 3)) < 1e-7,
                "Lift does zero work; quadratic drag always dissipates kinetic energy");
        Vec3 turning = FlightMotion.velocity(flat, control(2, 1, 0, 90, 0, false, false));
        check(turning.x < 0 && turning.z > 0, "Freefall steering turns existing momentum instead of snapping the vector to view yaw");
        Vec3 diving = FlightMotion.velocity(flat, control(2, 1, 0, 0, 90, true, false));
        check(diving.z > 0 && diving.horizontalDistance() < flat.horizontalDistance(),
                "Entering a vertical dive removes lateral speed by drag instead of teleporting momentum to zero");
        Vec3 started = FlightMotion.velocity(Vec3.ZERO, control(2, 1, 0, 0, 90, true, false));
        check(-started.y * 20 < 2 && -started.y * 20 > 1, "Freefall begins with gravitational acceleration rather than an immediate terminal velocity");
    }
    private static FlightMotion.Control control(int stage, float forward, float strafe, float yaw, float pitch,
            boolean fast, boolean glide) {
        return new FlightMotion.Control(stage, forward, strafe, yaw, pitch, fast, glide);
    }
    private static Vec3 settle(Vec3 initial, FlightMotion.Control control) {
        Vec3 velocity = initial;
        for (int tick = 0; tick < 4000; tick++) velocity = FlightMotion.velocity(velocity, control);
        return velocity;
    }
    private static boolean close(double first, double second) { return Math.abs(first - second) < 1e-6; }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
