package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, server-safe rigid-footprint motion. The caller supplies the
 * rotated model's conservative world AABB and already-loaded block collision
 * boxes; this class neither reads chunks nor runs any entity lifecycle code.
 *
 * The swept-AABB time-of-impact test clips the complete travel interval, rather
 * than only the destination or three separate axis paths. Thus the client's
 * straight interpolation between ticks cannot cross a wall that happened to
 * lie between the separate axis paths of a fast diagonal throw.
 */
public final class GroundLootPhysics {
    public static final double GRAVITY = .04;
    public static final double DRAG = .98;
    private static final double EPSILON = 1.0E-7;
    private static final double TIME_EPSILON = 1.0E-10;

    private GroundLootPhysics() {}

    /** Movement this tick and remaining velocity for the following tick. */
    public record Step(Vec3 movement, Vec3 velocity, boolean grounded,
                       boolean collidedX, boolean collidedY, boolean collidedZ) {}

    /** Gravity matches a vanilla ItemEntity; yaw is deliberately not involved. */
    public static Vec3 fallingVelocity(Vec3 velocity) {
        requireFinite(velocity);
        return velocity.add(0, -GRAVITY, 0);
    }

    /** Broad phase only: every position along the complete displacement is enclosed. */
    public static AABB sweepBounds(AABB bounds, Vec3 movement) {
        requireFinite(bounds);
        requireFinite(movement);
        return bounds.expandTowards(movement).inflate(EPSILON);
    }

    /** Always probes gravity again, so removing a supporting block releases the item. */
    public static Step step(AABB bounds, Vec3 velocity, List<AABB> blockers) {
        return sweep(bounds, fallingVelocity(velocity), blockers);
    }

    /**
     * Clips an already chosen displacement. Starting inside a solid box is not
     * repaired here: local spawn/legacy overlap recovery belongs to the caller.
     * Every blocked component is stopped, never reflected into a bounce. The
     * remaining displacement stops at the first contact for this tick. Tangent
     * velocity is retained for the next tick, allowing sliding without a bent
     * within-tick path that ordinary client interpolation could cut across.
     */
    public static Step sweep(AABB bounds, Vec3 desiredMovement, List<AABB> blockers) {
        requireFinite(bounds);
        requireFinite(desiredMovement);
        Objects.requireNonNull(blockers, "blockers");
        double fraction = 1;
        boolean hitX = false, hitY = false, hitZ = false;
        List<Contact> contacts = new ArrayList<>();
        for (AABB blocker : blockers) {
            requireFinite(blocker);
            Contact contact = contact(bounds, desiredMovement, blocker);
            if (contact == null) continue;
            contacts.add(contact);
            fraction = Math.min(fraction, contact.fraction);
        }
        // Select normals after the minimum time is known. Approximate ties must
        // not accumulate through an order-dependent chain of near contacts.
        for (Contact contact : contacts) {
            if (Math.abs(contact.fraction - fraction) <= TIME_EPSILON) {
                hitX |= contact.x;
                hitY |= contact.y;
                hitZ |= contact.z;
            }
        }
        Vec3 velocity = new Vec3(hitX ? 0 : damp(desiredMovement.x),
                hitY ? 0 : damp(desiredMovement.y), hitZ ? 0 : damp(desiredMovement.z));
        return new Step(desiredMovement.scale(fraction), velocity,
                desiredMovement.y < 0 && hitY, hitX, hitY, hitZ);
    }

    private static double damp(double value) {
        double result = value * DRAG;
        return Math.abs(result) < EPSILON ? 0 : result;
    }

    private record Contact(double fraction, boolean x, boolean y, boolean z) {}

    private static Contact contact(AABB moving, Vec3 displacement, AABB obstacle) {
        double entry = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        double entryX = Double.NEGATIVE_INFINITY, entryY = Double.NEGATIVE_INFINITY,
                entryZ = Double.NEGATIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double distance = axis == 0 ? displacement.x : axis == 1 ? displacement.y : displacement.z;
            if (distance == 0) {
                // A shared edge while moving parallel is not a blocking face.
                if (maximum(moving, axis) <= minimum(obstacle, axis)
                        || minimum(moving, axis) >= maximum(obstacle, axis)) return null;
                continue;
            }
            double axisEntry, axisExit;
            if (distance > 0) {
                axisEntry = (minimum(obstacle, axis) - maximum(moving, axis)) / distance;
                axisExit = (maximum(obstacle, axis) - minimum(moving, axis)) / distance;
            } else {
                axisEntry = (maximum(obstacle, axis) - minimum(moving, axis)) / distance;
                axisExit = (minimum(obstacle, axis) - maximum(moving, axis)) / distance;
            }
            entry = Math.max(entry, axisEntry);
            exit = Math.min(exit, axisExit);
            if (axis == 0) entryX = axisEntry;
            else if (axis == 1) entryY = axisEntry;
            else entryZ = axisEntry;
        }
        // Negative entry means initial overlap or motion away from a face.
        // The caller handles initial overlap rather than guessing a teleport.
        if (entry < -TIME_EPSILON || entry > 1 || exit < entry || exit < 0
                || !Double.isFinite(entry)) return null;
        return new Contact(Math.max(0, entry), Math.abs(entryX - entry) <= TIME_EPSILON,
                Math.abs(entryY - entry) <= TIME_EPSILON, Math.abs(entryZ - entry) <= TIME_EPSILON);
    }

    private static double minimum(AABB box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double maximum(AABB box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }

    private static void requireFinite(Vec3 vector) {
        Objects.requireNonNull(vector, "vector");
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_physics.ground_loot_motion_must_be_finite"));
        }
    }

    private static void requireFinite(AABB box) {
        Objects.requireNonNull(box, "box");
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                || box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_physics.ground_loot_collision_boxes_must_have_finite_positive_dimensions"));
        }
    }
}
