package com.example.examplemod.throwables.client;

import net.minecraft.world.phys.Vec3;

/** No renderer or world is initialized: checks formerly disappearing, end-on preview ribbons. */
public final class ThrowableTrajectoryGeometryChecks {
    private static int checks;

    public static void main(String[] args) {
        Vec3 right = new Vec3(1, 0, 0), up = new Vec3(0, 1, 0);
        for (Vec3 direction : new Vec3[]{new Vec3(0, 0, 1), new Vec3(0, 0, -1),
                new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(.2, .3, .7).normalize()}) {
            for (double length : new double[]{.0001, .01, 1, 20}) {
                for (double distance : new double[]{0, .01, 1, 90}) {
                    Vec3 segment = direction.scale(length), toward = direction.scale(distance);
                    Vec3 side = ThrowableTrajectoryGeometry.ribbonSide(segment, toward, right, up);
                    valid(side, direction);
                    // Approaching the eye-aligned direction cannot collapse or produce NaNs.
                    valid(ThrowableTrajectoryGeometry.ribbonSide(segment,
                            toward.add(1e-10, -1e-10, 1e-10), right, up), direction);
                }
            }
        }
        for (int angle = -1800; angle <= 1800; angle++) {
            double yaw = Math.toRadians(angle / 10.0);
            Vec3 direction = new Vec3(-Math.sin(yaw), .2, Math.cos(yaw)).normalize();
            valid(ThrowableTrajectoryGeometry.ribbonSide(direction, direction.scale(10), right, up), direction);
        }
        Vec3 before = ThrowableTrajectoryGeometry.ribbonSide(new Vec3(0, 0, -1),
                new Vec3(-1e-10, 0, -10), right, up);
        Vec3 after = ThrowableTrajectoryGeometry.ribbonSide(new Vec3(0, 0, -1),
                new Vec3(1e-10, 0, -10), right, up);
        check(before.distanceToSqr(after) < 1e-12, "Near-collinear mouse motion changed the fallback width axis");
        check(ThrowableTrajectoryGeometry.ribbonSide(Vec3.ZERO, Vec3.ZERO, right, up).equals(Vec3.ZERO),
                "A zero-length segment must stay empty");
        System.out.println("ThrowableTrajectoryGeometryChecks: " + checks + " checks passed; no game launched.");
    }

    private static void valid(Vec3 side, Vec3 direction) {
        check(Double.isFinite(side.x) && Double.isFinite(side.y) && Double.isFinite(side.z), "Ribbon width must be finite");
        check(Math.abs(side.lengthSqr() - 1) < 1e-9, "An eye-aligned ribbon must retain its width");
        check(Math.abs(side.dot(direction.normalize())) < 1e-9, "Ribbon width must stay perpendicular to motion");
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
