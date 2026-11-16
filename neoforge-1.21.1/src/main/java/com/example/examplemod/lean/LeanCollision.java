package com.example.examplemod.lean;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Soft body contact policy; eye sweeps and combat hitboxes are deliberately independent. */
final class LeanCollision {
    private static final double EPSILON = 1.0e-7;
    private static final Vec3[] WORLD_AXES = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
    private final List<Contact> contacts = new ArrayList<>();
    private record Face(int axis, boolean positive) {
        Vec3 normal() { return WORLD_AXES[axis]; }
        double depth(AABB body, AABB wall) {
            return positive ? maximum(wall, axis) - minimum(body, axis) : maximum(body, axis) - minimum(wall, axis);
        }
    }
    private record Contact(AABB wall, Face face, double[] budget) { }

    LeanCollision(AABB movementBox, LeanGeometry.Frame neutral, double tolerance, List<AABB> obstacles) {
        for (AABB wall : obstacles) {
            Face face = face(movementBox, wall);
            double[] budget = new double[neutral.boxes().size()];
            for (int index = 1; index < budget.length; index++) {
                var box = neutral.boxes().get(index);
                // A projection behind a wall face is not existing penetration unless the actual OBB touches it.
                double existing = intersects(box, wall) ? Math.max(0, face.depth(box.bounds(), wall)) : 0;
                budget[index] = existing + Math.max(0, tolerance);
            }
            contacts.add(new Contact(wall, face, budget));
        }
    }

    boolean blocked(LeanGeometry.Frame before, LeanGeometry.Frame after, int index, AABB swept) {
        var first = before.boxes().get(index);
        var last = after.boxes().get(index);
        double angle = Math.abs(after.rig().angle() - before.rig().angle());
        double radius = first.center().subtract(before.rig().seat()).distanceTo(before.rig().pivot()) + first.half().length();
        double seatRadius = Math.max(before.seatRadius(), after.seatRadius());
        Vec3 seat = after.rig().seat().lengthSqr() > 1.0e-20 ? after.rig().seat() : before.rig().seat();
        Vec3 seatDirection = seat.normalize();
        for (Contact contact : contacts) {
            if (!contact.wall().intersects(swept)) continue;
            Vec3 normal = contact.face().normal();
            double axial = Math.clamp(normal.dot(before.rig().axis()), -1, 1);
            // Bound curvature only along the wall normal. Tangential motion cannot deepen wall contact.
            double margin = radius * Math.sqrt(Math.max(0, 1 - axial * axial)) * angle * angle / 8
                    + seatRadius * Math.abs(normal.dot(seatDirection)) * angle / 2;
            double deepest = Math.max(contact.face().depth(first.bounds(), contact.wall()),
                    contact.face().depth(last.bounds(), contact.wall())) + margin;
            if (deepest <= contact.budget()[index] + EPSILON) continue;
            // The broad-phase AABB contains empty corners. An expanded OBB encloses the entire short
            // rigid rotation/seat path, rejecting those false contacts without tunnelling through thin blocks.
            double movement = (radius + seatRadius) * angle;
            if (intersects(first, contact.wall().inflate(movement))) return true;
        }
        return false;
    }

    private static Face face(AABB player, AABB wall) {
        Face selected = null;
        double closest = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) for (boolean positive : new boolean[]{false, true}) {
            Face next = new Face(axis, positive);
            double depth = next.depth(player, wall);
            // Prefer a separating face of the physical player box. If already overlapping, use its
            // shallowest escape face. This choice is fixed for the whole zero-to-target lean sweep.
            if (depth < closest) { closest = depth; selected = next; }
        }
        return selected;
    }

    static boolean intersects(LeanGeometry.Box box, AABB wall) {
        Vec3 center = wall.getCenter(), half = new Vec3(wall.getXsize(), wall.getYsize(), wall.getZsize()).scale(.5);
        Vec3 delta = center.subtract(box.center());
        Vec3[] axes = {box.right(), box.up(), box.forward()};
        for (Vec3 axis : WORLD_AXES) if (separated(box, half, delta, axis)) return false;
        for (Vec3 axis : axes) {
            if (separated(box, half, delta, axis)) return false;
            for (Vec3 world : WORLD_AXES) if (separated(box, half, delta, axis.cross(world))) return false;
        }
        return true;
    }

    private static boolean separated(LeanGeometry.Box box, Vec3 wallHalf, Vec3 delta, Vec3 axis) {
        double length = axis.length();
        if (length < 1.0e-10) return false;
        double body = Math.abs(axis.dot(box.right())) * box.half().x + Math.abs(axis.dot(box.up())) * box.half().y
                + Math.abs(axis.dot(box.forward())) * box.half().z;
        double wall = Math.abs(axis.x) * wallHalf.x + Math.abs(axis.y) * wallHalf.y + Math.abs(axis.z) * wallHalf.z;
        return Math.abs(axis.dot(delta)) >= body + wall - EPSILON * length;
    }
    private static double minimum(AABB box, int axis) { return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ; }
    private static double maximum(AABB box, int axis) { return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ; }
}
