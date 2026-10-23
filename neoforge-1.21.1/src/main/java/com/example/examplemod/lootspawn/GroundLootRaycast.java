package com.example.examplemod.lootspawn;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/** Geometric nearest-target selection shared by server validation and pure checks. */
public final class GroundLootRaycast {
    private GroundLootRaycast() {}

    public record Target(int id, AABB localBounds, Vec3 origin, float yaw) {}
    public record Hit(int id, Vec3 point, double distanceSquared) {}

    public static Optional<Hit> nearest(Vec3 eye, Vec3 end, double maximumDistanceSquared, List<Target> candidates) {
        Hit nearest = null;
        for (Target target : candidates) {
            var localHit = target.localBounds().clip(
                    GroundLootFootprints.localPoint(eye, target.origin(), target.yaw()),
                    GroundLootFootprints.localPoint(end, target.origin(), target.yaw()));
            if (localHit.isEmpty()) continue;
            Vec3 point = GroundLootFootprints.worldPoint(localHit.get(), target.origin(), target.yaw());
            double distance = eye.distanceToSqr(point);
            if (distance > maximumDistanceSquared + 1e-6) continue;
            if (nearest == null || distance < nearest.distanceSquared() - 1e-6
                    || Math.abs(distance - nearest.distanceSquared()) <= 1e-6 && target.id() < nearest.id()) {
                nearest = new Hit(target.id(), point, distance);
            }
        }
        return Optional.ofNullable(nearest);
    }
}
