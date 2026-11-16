package com.example.examplemod.lean.client;

import com.example.examplemod.lean.LeanGeometry;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Display-only stance blend. Lean input, yaw and collision limits are sampled immediately, never filtered. */
public final class LeanViewTransition {
    public static final double TRAVEL_TICKS = 4;
    public record Eyes(double standing, double crouching, double prone) { }
    private final double[] start = new double[3];
    private int target = -1;
    private double since, lastTime;

    public void reset() { target = -1; }

    public Vec3 offset(LeanGeometry.Pose pose, Eyes eyes, double fraction, double ticks) {
        int stance = pose.prone() ? 2 : pose.eyeHeight() < 1.5 * pose.scale() ? 1 : 0;
        if (target < 0 || ticks < lastTime) {
            java.util.Arrays.fill(start, 0);
            start[stance] = 1;
            target = stance;
            since = ticks;
        }
        double[] weights = weights(ticks);
        if (target != stance) {
            System.arraycopy(weights, 0, start, 0, 3);
            since = ticks;
            target = stance;
        }
        lastTime = ticks;
        if (Math.abs(fraction) < 1.0e-12) return Vec3.ZERO;
        Vec3 result = Vec3.ZERO;
        for (int index = 0; index < 3; index++) {
            if (weights[index] < 1.0e-12) continue;
            double height = index == 0 ? eyes.standing() : index == 1 ? eyes.crouching() : eyes.prone();
            var endpoint = new LeanGeometry.Pose(pose.viewYaw(), pose.bodyYaw(), height,
                    pose.scale(), index == 2, pose.headPitch());
            result = result.add(LeanGeometry.offset(endpoint, fraction).scale(weights[index]));
        }
        return result;
    }

    private double[] weights(double ticks) {
        double progress = Math.clamp((ticks - since) / TRAVEL_TICKS, 0, 1);
        double eased = progress * progress * (3 - 2 * progress);
        double[] weights = new double[3];
        for (int index = 0; index < 3; index++)
            weights[index] = start[index] * (1 - eased) + (index == target ? eased : 0);
        return weights;
    }

    /** Sweep an eye-sized box, rather than just checking endpoints or a handful of rays. */
    public static double sweepFraction(Vec3 from, Vec3 to, Iterable<AABB> obstacles, double radius) {
        Vec3 delta = to.subtract(from);
        double limit = 1;
        for (AABB obstacle : obstacles) {
            AABB box = obstacle.inflate(radius);
            double near = 0, far = limit;
            double[] origins = {from.x, from.y, from.z};
            double[] directions = {delta.x, delta.y, delta.z};
            double[] minimum = {box.minX, box.minY, box.minZ}, maximum = {box.maxX, box.maxY, box.maxZ};
            boolean intersects = true;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(directions[axis]) < 1.0e-12) {
                    if (origins[axis] < minimum[axis] || origins[axis] > maximum[axis]) { intersects = false; break; }
                } else {
                    double a = (minimum[axis] - origins[axis]) / directions[axis];
                    double b = (maximum[axis] - origins[axis]) / directions[axis];
                    near = Math.max(near, Math.min(a, b));
                    far = Math.min(far, Math.max(a, b));
                    if (near > far) { intersects = false; break; }
                }
            }
            if (intersects) limit = Math.min(limit, Math.max(0, near - 1.0e-6));
        }
        return limit;
    }
}
