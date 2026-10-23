package com.example.examplemod.throwables.client;

import net.minecraft.world.phys.Vec3;

/** Camera-facing ribbon width, including a trajectory pointing directly at the eye. */
final class ThrowableTrajectoryGeometry {
    private ThrowableTrajectoryGeometry() {}

    static Vec3 ribbonSide(Vec3 segment, Vec3 toMiddle, Vec3 cameraRight, Vec3 cameraUp) {
        double length = segment.length();
        if (length < 1e-9) return Vec3.ZERO;
        Vec3 direction = segment.scale(1 / length);
        Vec3 side = direction.cross(toMiddle);
        // Normalizing an almost parallel cross product amplifies roundoff, then abruptly drops
        // to ZERO in Vec3.normalize(). A camera-axis fallback keeps the ribbon finite and visible.
        if (side.lengthSqr() <= Math.max(1, toMiddle.lengthSqr()) * 1e-12) {
            side = cameraRight.subtract(direction.scale(cameraRight.dot(direction)));
            if (side.lengthSqr() < 1e-12)
                side = cameraUp.subtract(direction.scale(cameraUp.dot(direction)));
        }
        double width = side.length();
        return width < 1e-12 ? Vec3.ZERO : side.scale(1 / width);
    }
}
