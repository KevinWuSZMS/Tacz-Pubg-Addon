package com.example.examplemod.lean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared world-space lean rig. Its boxes are combat geometry, never the player's movement AABB. */
public final class LeanGeometry {
    public static final double SIDE_DISTANCE = .35;
    public static final double HEAD_DROP = .10;
    public static final double ROLL_RADIANS = Math.toRadians(22);
    private static final double EPSILON = 1.0e-7;
    private LeanGeometry() { }

    /** Positive fraction is screen-right: at yaw zero that is world -X. */
    public static Vec3 offset(double yawRadians, double fraction) {
        double value = bounded(fraction);
        return new Vec3(-Math.cos(yawRadians) * SIDE_DISTANCE * value,
                -HEAD_DROP * Math.abs(value), -Math.sin(yawRadians) * SIDE_DISTANCE * value);
    }
    public static Vec3 offset(Player player, double fraction) {
        return offset(Math.toRadians(player.getYRot()), fraction).scale(player.getScale());
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
    public record Frame(List<Box> boxes, AABB bounds, double fraction) {
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

    public static Frame frame(Player player, double fraction) { return frame(player, fraction, player.getYRot()); }
    public static Frame frame(Player player, double fraction, float viewYaw) {
        boolean prone = player.getPose() == Pose.SWIMMING;
        return frame(player.position(), Math.toRadians(viewYaw), Math.toRadians(player.yBodyRot),
                player.getEyeHeight(), player.getScale(), prone, fraction,
                prone ? -Math.PI / 4 : Math.toRadians(player.getXRot()));
    }
    /** Pure rig shared with offline checks. Body yaw locates shoulders; view yaw controls the lean direction. */
    public static Frame frame(Vec3 feet, double viewYaw, double bodyYaw, double eyeHeight,
            double scale, boolean prone, double fraction) {
        return frame(feet, viewYaw, bodyYaw, eyeHeight, scale, prone, fraction, prone ? -Math.PI / 4 : 0);
    }
    public static Frame frame(Vec3 feet, double viewYaw, double bodyYaw, double eyeHeight,
            double scale, boolean prone, double fraction, double headPitch) {
        double value = bounded(fraction), angle = ROLL_RADIANS * value;
        Vec3 delta = offset(viewYaw, value).scale(scale), forward = forward(bodyYaw), right = right(bodyYaw);
        Vec3 rollAxis = forward(viewYaw), tiltedRight = rotate(right, rollAxis, angle);
        Vec3 tiltedUp = rotate(new Vec3(0, 1, 0), rollAxis, angle), tiltedForward = rotate(forward, rollAxis, angle);
        var boxes = new ArrayList<Box>();
        Vec3 neckCenter, chestCenter;
        if (prone) {
            neckCenter = feet.add(forward.scale(.4071875 * scale)).add(0, .30 * scale, 0);
            chestCenter = feet.add(forward.scale(.055625 * scale)).add(0, .30 * scale, 0);
            boxes.add(new Box(feet.add(forward.scale(-.6475 * scale)).add(0, .30 * scale, 0), right,
                    new Vec3(0, 1, 0), forward, new Vec3(.234375, .1171875, .3515625).scale(scale), false));
        } else {
            boolean crouching = eyeHeight < 1.5 * scale;
            neckCenter = feet.add(0, (crouching ? 1.03609375 : 1.4071875) * scale, 0);
            chestCenter = feet.add(0, (crouching ? .786165 : 1.055625) * scale, 0)
                    .add(forward.scale(crouching ? -.16855 * scale : 0));
            double legHeight = Math.max(.25 * scale, chestCenter.y - feet.y - .25 * scale);
            boxes.add(new Box(feet.add(0, legHeight / 2, 0), right, new Vec3(0, 1, 0), forward,
                    new Vec3(.30 * scale, legHeight / 2, .20 * scale), false));
        }
        // Native head rotates about the neck before lean. In full prone vanilla raises it by 45 degrees.
        double headYaw = viewYaw - bodyYaw, sh = Math.sin(headYaw), ch = Math.cos(headYaw);
        double sp = Math.sin(headPitch), cp = Math.cos(headPitch);
        Vec3 basisX = right.scale(-1), basisY = prone ? forward.scale(-1) : new Vec3(0, -1, 0);
        Vec3 basisZ = prone ? new Vec3(0, 1, 0) : forward.scale(-1);
        Vec3 nativeHeadX = basisX.scale(ch).add(basisZ.scale(-sh));
        Vec3 nativeHeadY = basisX.scale(sh * sp).add(basisY.scale(cp)).add(basisZ.scale(ch * sp));
        Vec3 nativeHeadZ = basisX.scale(sh * cp).add(basisY.scale(-sp)).add(basisZ.scale(ch * cp));
        Vec3 headCenter = neckCenter.subtract(nativeHeadY.scale(.234375 * scale));
        boxes.add(new Box(headCenter.add(delta), rotate(nativeHeadX, rollAxis, angle),
                rotate(nativeHeadY, rollAxis, angle), rotate(nativeHeadZ, rollAxis, angle),
                new Vec3(.234375, .234375, .234375).scale(scale), true));
        Vec3 bodyHalf = (prone ? new Vec3(.234375, .1171875, .3515625) : new Vec3(.25, .375, .16)).scale(scale);
        Vec3 armHalf = (prone ? new Vec3(.1171875, .1171875, .3515625) : new Vec3(.125, .375, .125)).scale(scale);
        Vec3 chestUp = tiltedUp, chestForward = tiltedForward;
        if (!prone && eyeHeight < 1.5 * scale) {
            chestUp = rotate(rotate(new Vec3(0, 1, 0), right, -.5), rollAxis, angle);
            chestForward = rotate(rotate(forward, right, -.5), rollAxis, angle);
        }
        boxes.add(new Box(chestCenter.add(delta.scale(.5)), tiltedRight, chestUp, chestForward, bodyHalf, false));
        for (int side : new int[]{-1, 1}) {
            boxes.add(new Box(chestCenter.add(tiltedRight.scale(side * .3515625 * scale)).add(delta.scale(.5)),
                    tiltedRight, tiltedUp, tiltedForward, armHalf, false));
        }
        AABB bounds = boxes.getFirst().bounds();
        for (int index = 1; index < boxes.size(); index++) bounds = bounds.minmax(boxes.get(index).bounds());
        return new Frame(boxes, bounds, value);
    }
    private static Vec3 right(double yaw) { return new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)); }
    private static Vec3 forward(double yaw) { return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)); }
    private static Vec3 rotate(Vec3 value, Vec3 axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return value.scale(cos).add(axis.cross(value).scale(sin)).add(axis.scale(axis.dot(value) * (1 - cos)));
    }

    public static double clamp(Player player, double proposedFraction) { return clamp(player, proposedFraction, player.getYRot()); }
    public static double clamp(Player player, double proposedFraction, float yaw) {
        double proposed = bounded(proposedFraction);
        if (Math.abs(proposed) < EPSILON) return 0;
        Frame neutral = frame(player, 0, yaw);
        Frame target = frame(player, proposed, yaw);
        Vec3 eyes = player.getEyePosition(), eyeTarget = eyes.add(offset(Math.toRadians(yaw), proposed).scale(player.getScale()));
        AABB envelope = new AABB(eyes, eyeTarget).inflate(.08 * player.getScale());
        for (int index = 1; index < neutral.boxes().size(); index++) {
            envelope = envelope.minmax(sweptBounds(neutral, target, index));
        }
        // Collect voxel collisions once, including the arms' full arc around the common chest pivot.
        List<AABB> obstacles = new ArrayList<>();
        for (var shape : player.level().getBlockCollisions(player, envelope)) obstacles.addAll(shape.toAabbs());
        if (obstacles.isEmpty() && player.level().getWorldBorder().isWithinBounds(envelope)) return proposed;
        return clampPath(proposed, (from, to) -> clearPath(player, neutral, from, to, yaw, obstacles));
    }
    @FunctionalInterface public interface Sweep { boolean clear(double from, double to); }
    /** Sweep in short intervals before refining first contact; clear endpoints alone cannot skip a thin obstacle. */
    public static double clampPath(double proposedFraction, Sweep sweep) {
        double proposed = bounded(proposedFraction);
        double previous = 0;
        for (int step = 1; step <= 24; step++) {
            double next = proposed * step / 24;
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
    private static boolean clearPath(Player player, Frame neutral, double previous, double next, float yaw, List<AABB> obstacles) {
        Vec3 eyes = player.getEyePosition(), start = eyes.add(offset(Math.toRadians(yaw), previous).scale(player.getScale()));
        Vec3 end = eyes.add(offset(Math.toRadians(yaw), next).scale(player.getScale()));
        double radius = .08 * player.getScale();
        if (blocked(player, new AABB(start, end).inflate(radius), null, obstacles)) return false;
        Frame before = frame(player, previous, yaw), after = frame(player, next, yaw);
        // A pre-existing shoulder overlap from vanilla's narrower movement box is not newly occupied space.
        for (int index = 1; index < after.boxes().size(); index++) {
            AABB swept = sweptBounds(before, after, index);
            if (blocked(player, swept, neutral.boxes().get(index).bounds(), obstacles)) return false;
        }
        return true;
    }
    /** Bounds a same-side lean interval; its center translation is linear and its corners follow rotation arcs. */
    static AABB sweptBounds(Frame before, Frame after, int index) {
        Box startBox = before.boxes().get(index), endBox = after.boxes().get(index);
        double radius = startBox.half().length();
        // Head/body rotate about their own centers; each arm now also travels around the chest center.
        if (index >= 3) radius += startBox.center().distanceTo(before.boxes().get(2).center());
        double arcMargin = radius * (1 - Math.cos(ROLL_RADIANS * (after.fraction() - before.fraction()) / 2));
        return startBox.bounds().minmax(endBox.bounds()).inflate(arcMargin);
    }
    private static boolean blocked(Player player, AABB bounds, AABB previouslyOccupied, List<AABB> obstacles) {
        if (!player.level().getWorldBorder().isWithinBounds(bounds)) return true;
        for (AABB block : obstacles) {
            if (!block.intersects(bounds)) continue;
            if (previouslyOccupied == null) return true;
            AABB overlap = block.intersect(bounds);
            if (overlap.minX < previouslyOccupied.minX - EPSILON || overlap.maxX > previouslyOccupied.maxX + EPSILON
                    || overlap.minY < previouslyOccupied.minY - EPSILON || overlap.maxY > previouslyOccupied.maxY + EPSILON
                    || overlap.minZ < previouslyOccupied.minZ - EPSILON || overlap.maxZ > previouslyOccupied.maxZ + EPSILON) return true;
        }
        return false;
    }
}
