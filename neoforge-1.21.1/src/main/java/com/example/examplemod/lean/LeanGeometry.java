package com.example.examplemod.lean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared world-space lean rig. Its boxes are combat geometry, never the player's movement AABB. */
public final class LeanGeometry {
    public static final double ROLL_RADIANS = Math.toRadians(22);
    public static final double EYE_RADIUS = .08;
    private static final double MODEL_UNIT = .9375 / 16;
    private static final double WAIST_HALF_WIDTH = 4 * MODEL_UNIT;
    private static final double WAIST_HALF_DEPTH = 2 * MODEL_UNIT;
    private static final double HALF_CHEST_HEIGHT = 6 * MODEL_UNIT;
    private static final int SWEEP_STEPS = 24;
    private static final double EPSILON = 1.0e-7;
    private LeanGeometry() { }

    /** View/body angles are radians; eye height already includes entity scale. */
    public record Pose(double viewYaw, double bodyYaw, double eyeHeight, double scale, boolean prone, double headPitch) { }

    /** A single rigid upper-body transform; points and pivot are relative to the player's feet. */
    public record Rig(Vec3 pivot, Vec3 axis, Vec3 seat, double angle) {
        public Vec3 point(Vec3 value) {
            if (angle == 0 && seat.lengthSqr() == 0) return value;
            return pivot.add(direction(value.subtract(pivot))).add(seat);
        }
        public Vec3 direction(Vec3 value) { return angle == 0 ? value : rotate(value, axis, angle); }
        public Vec3 offset(Vec3 value) { return point(value).subtract(value); }
        private Rig at(Vec3 feet) { return new Rig(pivot.add(feet), axis, seat, angle); }
    }

    public static Pose snapshot(Player player) { return snapshot(player, player.getYRot()); }
    public static Pose snapshot(Player player, float viewYaw) {
        boolean prone = player.getPose() == net.minecraft.world.entity.Pose.SWIMMING;
        return new Pose(Math.toRadians(viewYaw), Math.toRadians(player.yBodyRot), player.getEyeHeight(),
                player.getScale(), prone, prone ? -Math.PI / 4 : Math.toRadians(player.getXRot()));
    }

    /** Sample the same rig at the native renderer's interpolation time. Server snapshots stay tick-based. */
    public static Pose renderSnapshot(Player player, float partialTick) {
        boolean prone = player.getPose() == net.minecraft.world.entity.Pose.SWIMMING;
        return new Pose(Math.toRadians(player.getViewYRot(partialTick)),
                Math.toRadians(Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot)), player.getEyeHeight(),
                player.getScale(), prone,
                prone ? -Math.PI / 4 : Math.toRadians(player.getViewXRot(partialTick)));
    }

    /** The old two-argument entry point now describes the ordinary standing rig. */
    public static Vec3 offset(double yawRadians, double fraction) {
        return offset(new Pose(yawRadians, yawRadians, 1.62, 1, false, 0), fraction);
    }
    public static Vec3 offset(Player player, double fraction) { return offset(snapshot(player), fraction); }
    public static Vec3 offset(Pose pose, double fraction) {
        return rig(pose, fraction).offset(new Vec3(0, pose.eyeHeight(), 0));
    }

    private record NativeTorso(Vec3 neck, Vec3 chest, Vec3 spine, Vec3 depth, Vec3 right, Vec3 forward) { }
    private static NativeTorso torso(Pose pose) {
        double scale = pose.scale();
        Vec3 forward = forward(pose.bodyYaw()), right = right(pose.bodyYaw());
        if (pose.prone()) {
            return new NativeTorso(forward.scale(.4071875 * scale).add(0, .30 * scale, 0),
                    forward.scale(.055625 * scale).add(0, .30 * scale, 0), forward, new Vec3(0, 1, 0), right, forward);
        }
        boolean crouching = pose.eyeHeight() < 1.5 * scale;
        Vec3 spine = new Vec3(0, 1, 0), depth = forward;
        double neckY = 1.4071875, chestY = 1.055625, chestForward = 0;
        if (crouching) {
            spine = rotate(spine, right, -.5);
            depth = rotate(depth, right, -.5);
            neckY = 1.4071875 - .125 - 4.2 * MODEL_UNIT;
            chestY = 1.4071875 - .125 - (3.2 + 6 * Math.cos(.5)) * MODEL_UNIT;
            chestForward = -6 * Math.sin(.5) * MODEL_UNIT;
        }
        return new NativeTorso(new Vec3(0, neckY * scale, 0),
                forward.scale(chestForward * scale).add(0, chestY * scale, 0), spine, depth, right, forward);
    }

    public static Rig rig(Pose pose, double fraction) {
        NativeTorso torso = torso(pose);
        double angle = ROLL_RADIANS * bounded(fraction), scale = pose.scale();
        Vec3 pivot = torso.chest().subtract(torso.spine().scale(HALF_CHEST_HEIGHT * scale));
        Vec3 axis = forward(pose.viewYaw());
        if (angle == 0) return new Rig(pivot, axis, Vec3.ZERO, 0);
        if (crouching(pose)) {
            // Crouched thighs have a horizontal top plane, distinct from the pitched chest bottom.
            // Each waist depth edge is enclosed by its complete native body-yaw circle.
            double legTopY = (1.4071875 - .125 - 12.2 * MODEL_UNIT) * scale;
            double distance = 0;
            for (int depth : new int[]{-2, 2}) {
                WaistCircle circle = crouchWaistCircle(pose, depth);
                double highestY = pivot.y + rotate(circle.center().subtract(pivot), axis, angle).y
                        + circle.radius() * Math.abs(Math.sin(angle));
                distance = Math.max(distance, highestY - legTopY);
            }
            return new Rig(pivot, axis, new Vec3(0, -distance, 0), angle);
        }
        // The waist's circumscribed disk also covers native attack-animation twists about the spine.
        // Its support against the rotated leg-facing normal seats every such corner on the leg side.
        // The whole upper body receives this translation, retaining the native neck and shoulders.
        Vec3 intoLegs = torso.spine().scale(-1);
        double normalRight = intoLegs.dot(rotate(torso.right(), axis, angle));
        double normalDepth = intoLegs.dot(rotate(torso.depth(), axis, angle));
        double distance = Math.hypot(WAIST_HALF_WIDTH, WAIST_HALF_DEPTH) * scale
                * Math.hypot(normalRight, normalDepth);
        return new Rig(pivot, axis, intoLegs.scale(distance), angle);
    }

    private static boolean crouching(Pose pose) { return !pose.prone() && pose.eyeHeight() < 1.5 * pose.scale(); }
    private record WaistCircle(Vec3 center, double radius) { }
    private static WaistCircle crouchWaistCircle(Pose pose, int depth) {
        double unit = MODEL_UNIT * pose.scale();
        double bodyOriginY = (1.4071875 - .125 - 3.2 * MODEL_UNIT) * pose.scale();
        double circleY = bodyOriginY - (12 * Math.cos(.5) - depth * Math.sin(.5)) * unit;
        double radius = Math.hypot(4, 12 * Math.sin(.5) + depth * Math.cos(.5)) * unit;
        return new WaistCircle(new Vec3(0, circleY, 0), radius);
    }
    private static double seatRadius(Pose pose, Vec3 pivot) {
        if (!crouching(pose)) return Math.hypot(WAIST_HALF_WIDTH, WAIST_HALF_DEPTH) * pose.scale();
        double bound = 0;
        for (int depth : new int[]{-2, 2}) {
            WaistCircle circle = crouchWaistCircle(pose, depth);
            bound = Math.max(bound, circle.center().distanceTo(pivot) + circle.radius());
        }
        return bound;
    }
    public static double bounded(double fraction) { return Double.isFinite(fraction) ? Math.clamp(fraction, -1, 1) : 0; }

    public record Box(Vec3 center, Vec3 right, Vec3 up, Vec3 forward, Vec3 half, boolean head) {
        public AABB bounds() {
            double x = Math.abs(right.x) * half.x + Math.abs(up.x) * half.y + Math.abs(forward.x) * half.z;
            double y = Math.abs(right.y) * half.x + Math.abs(up.y) * half.y + Math.abs(forward.y) * half.z;
            double z = Math.abs(right.z) * half.x + Math.abs(up.z) * half.y + Math.abs(forward.z) * half.z;
            return new AABB(center.x - x, center.y - y, center.z - z, center.x + x, center.y + y, center.z + z);
        }
        public Optional<Vec3> intersect(Vec3 from, Vec3 to) {
            Vec3 local = from.subtract(center), delta = to.subtract(from);
            double near = 0, far = 1;
            Vec3[] axes = {right, up, forward};
            double[] extents = {half.x, half.y, half.z};
            for (int index = 0; index < 3; index++) {
                double origin = local.dot(axes[index]), direction = delta.dot(axes[index]);
                if (Math.abs(direction) < EPSILON) {
                    if (origin < -extents[index] || origin > extents[index]) return Optional.empty();
                } else {
                    double first = (-extents[index] - origin) / direction, second = (extents[index] - origin) / direction;
                    near = Math.max(near, Math.min(first, second));
                    far = Math.min(far, Math.max(first, second));
                    if (near > far) return Optional.empty();
                }
            }
            return Optional.of(from.add(delta.scale(near)));
        }
    }
    public record Hit(Vec3 point, boolean head) { }
    public record Frame(List<Box> boxes, AABB bounds, double fraction, Rig rig, double seatRadius) {
        public Frame { boxes = List.copyOf(boxes); }
        public Optional<Hit> intersect(Vec3 start, Vec3 end) {
            Hit closest = null; double distance = Double.POSITIVE_INFINITY;
            for (Box box : boxes) {
                var point = box.intersect(start, end);
                if (point.isEmpty()) continue;
                double next = point.get().distanceToSqr(start);
                if (next < distance - EPSILON || Math.abs(next - distance) < EPSILON && box.head()) {
                    closest = new Hit(point.get(), box.head()); distance = next;
                }
            }
            return Optional.ofNullable(closest);
        }
    }

    public static Frame frame(Player player, double fraction) { return frame(player.position(), snapshot(player), fraction); }
    public static Frame frame(Player player, double fraction, float viewYaw) {
        return frame(player.position(), snapshot(player, viewYaw), fraction);
    }
    /** Pure rig shared with offline checks. Body yaw locates shoulders; view yaw controls the lean direction. */
    public static Frame frame(Vec3 feet, double viewYaw, double bodyYaw, double eyeHeight,
            double scale, boolean prone, double fraction) {
        return frame(feet, viewYaw, bodyYaw, eyeHeight, scale, prone, fraction, prone ? -Math.PI / 4 : 0);
    }
    public static Frame frame(Vec3 feet, double viewYaw, double bodyYaw, double eyeHeight,
            double scale, boolean prone, double fraction, double headPitch) {
        return frame(feet, new Pose(viewYaw, bodyYaw, eyeHeight, scale, prone, headPitch), fraction);
    }

    public static Frame frame(Vec3 feet, Pose pose, double fraction) {
        double value = bounded(fraction), scale = pose.scale();
        NativeTorso torso = torso(pose);
        Rig rig = rig(pose, value).at(feet);
        Vec3 forward = torso.forward(), right = torso.right();
        var boxes = new ArrayList<Box>();
        Vec3 neckCenter = feet.add(torso.neck()), chestCenter = feet.add(torso.chest());
        if (pose.prone()) {
            boxes.add(new Box(feet.add(forward.scale(-.6475 * scale)).add(0, .30 * scale, 0), right,
                    new Vec3(0, 1, 0), forward, new Vec3(.234375, .1171875, .3515625).scale(scale), false));
        } else {
            double legHeight = Math.max(.25 * scale, chestCenter.y - feet.y - .25 * scale);
            boxes.add(new Box(feet.add(0, legHeight / 2, 0), right, new Vec3(0, 1, 0), forward,
                    new Vec3(.30 * scale, legHeight / 2, .20 * scale), false));
        }
        // Native head rotates about the neck before lean. In full prone vanilla raises it by 45 degrees.
        double headYaw = pose.viewYaw() - pose.bodyYaw(), sh = Math.sin(headYaw), ch = Math.cos(headYaw);
        double sp = Math.sin(pose.headPitch()), cp = Math.cos(pose.headPitch());
        Vec3 basisX = right.scale(-1), basisY = pose.prone() ? forward.scale(-1) : new Vec3(0, -1, 0);
        Vec3 basisZ = pose.prone() ? new Vec3(0, 1, 0) : forward.scale(-1);
        Vec3 nativeHeadX = basisX.scale(ch).add(basisZ.scale(-sh));
        Vec3 nativeHeadY = basisX.scale(sh * sp).add(basisY.scale(cp)).add(basisZ.scale(ch * sp));
        Vec3 nativeHeadZ = basisX.scale(sh * cp).add(basisY.scale(-sp)).add(basisZ.scale(ch * cp));
        Vec3 headCenter = neckCenter.subtract(nativeHeadY.scale(.234375 * scale));
        boxes.add(new Box(rig.point(headCenter), rig.direction(nativeHeadX),
                rig.direction(nativeHeadY), rig.direction(nativeHeadZ),
                new Vec3(.234375, .234375, .234375).scale(scale), true));
        Vec3 bodyHalf = (pose.prone() ? new Vec3(.234375, .1171875, .3515625) : new Vec3(.25, .375, .16)).scale(scale);
        Vec3 armHalf = (pose.prone() ? new Vec3(.1171875, .1171875, .3515625) : new Vec3(.125, .375, .125)).scale(scale);
        Vec3 chestUp = pose.prone() ? torso.depth() : torso.spine();
        Vec3 chestForward = pose.prone() ? torso.spine() : torso.depth();
        boxes.add(new Box(rig.point(chestCenter), rig.direction(right), rig.direction(chestUp),
                rig.direction(chestForward), bodyHalf, false));
        for (int side : new int[]{-1, 1}) {
            boxes.add(new Box(rig.point(chestCenter.add(right.scale(side * .3515625 * scale))),
                    rig.direction(right), rig.direction(new Vec3(0, 1, 0)), rig.direction(forward), armHalf, false));
        }
        AABB bounds = boxes.getFirst().bounds();
        for (int index = 1; index < boxes.size(); index++) bounds = bounds.minmax(boxes.get(index).bounds());
        return new Frame(boxes, bounds, value, rig, seatRadius(pose, rig.pivot().subtract(feet)));
    }
    private static Vec3 right(double yaw) { return new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)); }
    private static Vec3 forward(double yaw) { return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)); }
    private static Vec3 rotate(Vec3 value, Vec3 axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return value.scale(cos).add(axis.cross(value).scale(sin)).add(axis.scale(axis.dot(value) * (1 - cos)));
    }

    public static double clamp(Player player, double proposedFraction) { return clamp(player, proposedFraction, snapshot(player)); }
    public static double clamp(Player player, double proposedFraction, float yaw) {
        return clamp(player, proposedFraction, snapshot(player, yaw));
    }
    public static double clamp(Player player, Pose pose, double proposedFraction) {
        return clamp(player, proposedFraction, pose);
    }
    public static double clamp(Player player, double proposedFraction, Pose pose) {
        return clamp(player, player.position(), pose, proposedFraction);
    }

    /** Render callers supply interpolated feet; authoritative callers keep the current tick position. */
    public static double clamp(Player player, Vec3 feet, Pose pose, double proposedFraction) {
        double proposed = bounded(proposedFraction);
        if (Math.abs(proposed) < EPSILON) return 0;
        Vec3 eyes = feet.add(0, pose.eyeHeight(), 0);
        Frame neutral = frame(feet, pose, 0), target = frame(feet, pose, proposed);
        AABB envelope = sweptEyeBounds(neutral, target, eyes, EYE_RADIUS * pose.scale());
        for (int index = 1; index < neutral.boxes().size(); index++) {
            envelope = envelope.minmax(sweptBounds(neutral, target, index));
        }
        // Collect voxels once for the full hip-pivot arc, including the nonlinear seating translation.
        List<AABB> obstacles = new ArrayList<>();
        for (var shape : player.level().getBlockCollisions(player, envelope)) obstacles.addAll(shape.toAabbs());
        if (obstacles.isEmpty() && player.level().getWorldBorder().isWithinBounds(envelope)) return proposed;
        var settings = player.level().isClientSide ? PubgSettingsService.client() : PubgSettingsService.server(player.getServer());
        double tolerance = settings.number("lean.body_penetration_tolerance") * pose.scale();
        AABB movementBox = player.getBoundingBox().move(feet.subtract(player.position()));
        LeanCollision collision = new LeanCollision(movementBox, neutral, tolerance, obstacles);
        return clampPath(proposed, (from, to) -> clearPath(feet, pose, collision, from, to, obstacles,
                player.level().getWorldBorder()::isWithinBounds));
    }

    /** Same collision policy with supplied world shapes; bodyTolerance is already scaled to world units. */
    public static double clamp(Vec3 feet, Pose pose, AABB movementBox, double proposedFraction,
            double bodyTolerance, List<AABB> obstacles) {
        double proposed = bounded(proposedFraction);
        if (Math.abs(proposed) < EPSILON) return 0;
        LeanCollision collision = new LeanCollision(movementBox, frame(feet, pose, 0), bodyTolerance, obstacles);
        return clampPath(proposed, (from, to) -> clearPath(feet, pose, collision, from, to, obstacles, box -> true));
    }
    @FunctionalInterface public interface Sweep { boolean clear(double from, double to); }
    /** Sweep in short intervals before refining first contact; clear endpoints alone cannot skip a thin obstacle. */
    public static double clampPath(double proposedFraction, Sweep sweep) {
        double proposed = bounded(proposedFraction);
        double previous = 0;
        for (int step = 1; step <= SWEEP_STEPS; step++) {
            double next = proposed * step / SWEEP_STEPS;
            if (!sweep.clear(previous, next)) {
                double low = previous, high = next;
                for (int iteration = 0; iteration < 9; iteration++) {
                    double middle = (low + high) / 2;
                    if (sweep.clear(low, middle)) low = middle; else high = middle;
                }
                return low;
            }
            previous = next;
        }
        return proposed;
    }
    private static boolean clearPath(Vec3 feet, Pose pose, LeanCollision collision,
            double previous, double next, List<AABB> obstacles, Predicate<AABB> withinBorder) {
        Frame before = frame(feet, pose, previous), after = frame(feet, pose, next);
        Vec3 eyes = feet.add(0, pose.eyeHeight(), 0);
        AABB eyeSweep = sweptEyeBounds(before, after, eyes, EYE_RADIUS * pose.scale());
        if (!withinBorder.test(eyeSweep)) return false;
        for (AABB block : obstacles) if (block.intersects(eyeSweep)) return false;
        // Body contact is soft and directional; the eye path above stays strict.
        for (int index = 1; index < after.boxes().size(); index++) {
            AABB swept = sweptBounds(before, after, index);
            if (!withinBorder.test(swept) || collision.blocked(before, after, index, swept)) return false;
        }
        return true;
    }
    /** End boxes plus a bound on every hip-pivot corner arc and the seating function between them. */
    static AABB sweptBounds(Frame before, Frame after, int index) {
        Box startBox = before.boxes().get(index), endBox = after.boxes().get(index);
        if (index == 0) return startBox.bounds().minmax(endBox.bounds());
        double radius = startBox.center().subtract(before.rig().seat()).distanceTo(before.rig().pivot()) + startBox.half().length();
        return startBox.bounds().minmax(endBox.bounds()).inflate(sweepMargin(before, after, radius));
    }
    static AABB sweptEyeBounds(Frame before, Frame after, Vec3 neutralEye, double eyeRadius) {
        Vec3 start = before.rig().point(neutralEye), end = after.rig().point(neutralEye);
        double radius = neutralEye.distanceTo(before.rig().pivot());
        return new AABB(start, end).inflate(eyeRadius + sweepMargin(before, after, radius));
    }
    private static double sweepMargin(Frame before, Frame after, double radius) {
        double angle = Math.abs(after.rig().angle() - before.rig().angle());
        // Rotation's second derivative is bounded by radius: chord error <= r * angle^2 / 8.
        // Seat is a maximum of support functions. Its Lipschitz bound is the disk radius,
        // or (circle-center lever + circle radius) for crouch's pitched and twisted waist.
        // This remains valid across support changes and the max(0, requiredDrop) corner.
        return radius * angle * angle / 8 + Math.max(before.seatRadius(), after.seatRadius()) * angle / 2;
    }
}
