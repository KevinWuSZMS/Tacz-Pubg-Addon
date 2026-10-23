package com.example.examplemod.lootspawn;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pure collision fixtures: no world, renderer, client or server is started. */
public final class GroundLootPhysicsChecks {
    private static int checks;

    private GroundLootPhysicsChecks() {}

    public static void main(String[] args) {
        originalCarrierAllowsModelOverhang();
        gravityAndSupport();
        thinObstaclesAndCeilings();
        wallsStairsAndCorners();
        diagonalPathCannotTunnel();
        longRotatedFootprints();
        manyTickDrops();
        randomizedNonpenetration();
        invalidInputs();
        System.out.println("Ground loot gravity and swept-collision checks passed: " + checks);
    }

    private static void originalCarrierAllowsModelOverhang() {
        AABB carrier = GroundLootFootprints.collisionBounds().move(0, 2, 0);
        near(carrier.getXsize(), .22, "original carrier width is restored");
        near(carrier.getZsize(), .22, "original carrier depth is restored");
        near(carrier.getYsize(), .13, "original carrier height is restored");
        AABB left = new AABB(-3, 0, -3, -.15, 5, 3);
        AABB right = new AABB(.15, 0, -3, 3, 5, 3);
        AABB visibleRifle = new AABB(-1, 2, -.2, 1, 2.2, .2);
        check(visibleRifle.intersects(left) && visibleRifle.intersects(right),
                "the visible barrel and stock may overhang into neighboring walls");
        GroundLootPhysics.Step fall = GroundLootPhysics.step(carrier, Vec3.ZERO, List.of(left, right));
        near(fall.movement().y, -.04, "the small carrier falls freely through a gap narrower than the model");
        check(!fall.collidedX() && !fall.collidedZ() && !fall.grounded(),
                "visual overhang does not block gravity or mark a false landing");
        AABB floor = new AABB(-3, 0, -3, 3, 1, 3);
        GroundLootPhysics.Step landing = GroundLootPhysics.sweep(carrier, new Vec3(0, -50, 0),
                List.of(left, right, floor));
        near(carrier.move(landing.movement()).minY, 1, "the restored small carrier still cannot fall through its floor");
        check(landing.grounded(), "small-box ground contact remains detectable");
        GroundLootPhysics.Step released = GroundLootPhysics.step(carrier.move(landing.movement()),
                landing.velocity(), List.of(left, right));
        near(released.movement().y, -.04, "removing the small carrier support still resumes falling");
    }

    private static void gravityAndSupport() {
        AABB item = box(0, 4, 0, .6, .15, .25);
        GroundLootPhysics.Step first = GroundLootPhysics.step(item, Vec3.ZERO, List.of());
        near(first.movement().y, -.04, "gravity starts a stationary midair item falling");
        near(first.velocity().y, -.0392, "drag follows gravity instead of erasing it");
        check(!first.grounded() && !first.collidedY(), "an airborne item has no fake support");

        AABB floor = new AABB(-8, 0, -8, 8, 1, 8);
        item = box(0, 1, 0, .6, .15, .25);
        GroundLootPhysics.Step contact = GroundLootPhysics.step(item, new Vec3(0, -.8, 0), List.of(floor));
        near(contact.movement().y, 0, "resting model bottom remains on the surface");
        near(contact.velocity().y, 0, "ground contact stops downwards velocity without bounce");
        check(contact.grounded(), "a downward contact is recorded as ground");

        GroundLootPhysics.Step unsupported = GroundLootPhysics.step(item, contact.velocity(), List.of());
        near(unsupported.movement().y, -.04, "removing the floor releases a previously resting item immediately");
        check(!unsupported.grounded(), "a removed support cannot leave stale onGround state");

        Vec3 desired = new Vec3(.3, -.4, -.2);
        AABB broad = GroundLootPhysics.sweepBounds(item, desired);
        check(encloses(broad, item) && encloses(broad, item.move(desired)),
                "the broad-phase query encloses both origin and complete destination");
        check(encloses(broad, item.move(0, desired.y, desired.z)),
                "the broad phase also contains intermediate axis-sweep boxes");
    }

    private static void thinObstaclesAndCeilings() {
        // One twentieth of a block is much thinner than a fast tick displacement.
        for (double height : new double[] {.05, .125, .5, 1, 1.5}) {
            AABB floor = new AABB(-8, 0, -8, 8, height, 8);
            for (double speed : new double[] {.1, 1, 8, 64, 512}) {
                AABB item = box(0, height + .07, 0, .6, .15, .25);
                GroundLootPhysics.Step result = GroundLootPhysics.sweep(item, new Vec3(0, -speed, 0), List.of(floor));
                near(item.move(result.movement()).minY, height, "a downward sweep hits the exact thin/slab/fence surface");
                check(result.grounded() && result.collidedY() && result.velocity().y == 0,
                        "even a high-speed fall settles without a rebound");
                nonpenetrating(item.move(result.movement()), List.of(floor));
            }
        }
        AABB ceiling = new AABB(-8, 2, -8, 8, 2.05, 8);
        AABB item = box(0, 1, 0, .6, .15, .25);
        GroundLootPhysics.Step rise = GroundLootPhysics.sweep(item, new Vec3(0, 16, 0), List.of(ceiling));
        near(item.move(rise.movement()).maxY, 2, "upward motion cannot tunnel through a thin ceiling");
        check(rise.collidedY() && !rise.grounded() && rise.velocity().y == 0,
                "ceiling contact stops upward movement without pretending to land");
        GroundLootPhysics.Step next = GroundLootPhysics.step(item.move(rise.movement()), rise.velocity(), List.of(ceiling));
        near(next.movement().y, -.04, "an item that hit the ceiling falls away on the next tick");
    }

    private static void wallsStairsAndCorners() {
        AABB item = box(0, 1, 0, .6, .15, .25);
        for (int axis : new int[] {0, 2}) {
            for (int sign : new int[] {-1, 1}) {
                AABB wall = axis == 0
                        ? new AABB(sign > 0 ? 1 : -1.05, -3, -3, sign > 0 ? 1.05 : -1, 5, 3)
                        : new AABB(-3, -3, sign > 0 ? 1 : -1.05, 3, 5, sign > 0 ? 1.05 : -1);
                Vec3 desired = axis == 0 ? new Vec3(sign * 20, 0, .2) : new Vec3(.2, 0, sign * 20);
                GroundLootPhysics.Step hit = GroundLootPhysics.sweep(item, desired, List.of(wall));
                check(axis == 0 ? hit.collidedX() && hit.velocity().x == 0
                        : hit.collidedZ() && hit.velocity().z == 0, "thin walls stop high-speed motion in either direction");
                near(axis == 0 ? hit.velocity().z : hit.velocity().x, .2 * GroundLootPhysics.DRAG,
                        "wall collision preserves tangential velocity for the following tick");
                nonpenetrating(item.move(hit.movement()), List.of(wall));
            }
        }

        List<AABB> stairs = List.of(new AABB(-1, 0, -1, 1, .5, 1), new AABB(.5, .5, -1, 1, 1, 1));
        GroundLootPhysics.Step lower = GroundLootPhysics.sweep(box(-.2, 2, 0, .2, .15, .2),
                new Vec3(0, -8, 0), stairs);
        near(2 + lower.movement().y, .5, "a small item lands on the actual lower stair tread");
        GroundLootPhysics.Step upper = GroundLootPhysics.sweep(box(.7, 2, 0, .2, .15, .2),
                new Vec3(0, -8, 0), stairs);
        near(2 + upper.movement().y, 1, "a small item lands on the actual upper stair tread");
        AABB rifle = box(.3, 2, 0, 1.2, .15, .25);
        GroundLootPhysics.Step bridged = GroundLootPhysics.sweep(rifle, new Vec3(0, -8, 0), stairs);
        near(2 + bridged.movement().y, 1, "a long footprint stops at the highest tread it really overlaps");
        nonpenetrating(rifle.move(bridged.movement()), stairs);

        List<AABB> corner = List.of(new AABB(1, -2, -4, 1.05, 4, 4), new AABB(-4, -2, 1, 4, 4, 1.05));
        for (Vec3 diagonal : List.of(new Vec3(12, 0, 8), new Vec3(8, 0, 12), new Vec3(12, 0, 12))) {
            GroundLootPhysics.Step result = GroundLootPhysics.sweep(item, diagonal, corner);
            check(result.collidedX() || result.collidedZ(), "a diagonal throw stops at the first inside-corner face");
            nonpenetrating(item.move(result.movement()), corner);
            GroundLootPhysics.Step next = GroundLootPhysics.sweep(item.move(result.movement()), result.velocity(), corner);
            nonpenetrating(item.move(result.movement()).move(next.movement()), corner);
        }
    }

    private static void diagonalPathCannotTunnel() {
        // Axis-Y-first motion would move below this hanging wall, then move X
        // past it, even though the straight rendered flight goes through it.
        AABB hangingWall = new AABB(1, 1.5, -1, 1.05, 4, 1);
        AABB item = box(0, 4, 0, .2, .15, .2);
        Vec3 diagonal = new Vec3(4, -4, 0);
        GroundLootPhysics.Step result = GroundLootPhysics.sweep(item, diagonal, List.of(hangingWall));
        near(item.move(result.movement()).maxX, 1, "diagonal falling motion hits a hanging wall along the real travel line");
        check(result.collidedX(), "the hanging wall supplies a real horizontal contact normal");
        near(result.velocity().y, -4 * GroundLootPhysics.DRAG,
                "the item may fall tangentially down the wall on the next tick");
        for (int sample = 0; sample <= 50; sample++) {
            nonpenetrating(item.move(result.movement().scale(sample / 50.0)), List.of(hangingWall));
        }
        GroundLootPhysics.Step next = GroundLootPhysics.sweep(item.move(result.movement()), result.velocity(), List.of(hangingWall));
        check(next.movement().y < -1, "a contact does not permanently glue an airborne item to a wall");
        nonpenetrating(item.move(result.movement()).move(next.movement()), List.of(hangingWall));
    }

    private static void longRotatedFootprints() {
        AABB local = new AABB(-1.2, 0, -.2, 1.2, .16, .2);
        AABB floor = new AABB(-10, 0, -10, 10, .5, 10);
        AABB wall = new AABB(2, 0, -10, 2.05, 8, 10);
        for (float yaw : new float[] {0, 15, 45, 90, 135, 180, 270, 359}) {
            AABB rotated = GroundLootFootprints.worldBounds(local, new Vec3(0, 3, 0), yaw);
            GroundLootPhysics.Step drop = GroundLootPhysics.sweep(rotated, new Vec3(0, -32, 0), List.of(floor));
            near(rotated.move(drop.movement()).minY, .5, "every yaw settles the complete model footprint onto a slab");
            GroundLootPhysics.Step lateral = GroundLootPhysics.sweep(rotated, new Vec3(32, 0, 0), List.of(wall));
            near(rotated.move(lateral.movement()).maxX, 2, "every yaw stops the enclosing model box before the wall");
            nonpenetrating(rotated.move(lateral.movement()), List.of(wall));
        }
        AABB edge = new AABB(.8, 0, -.1, 1.0, 1, .1);
        AABB overhang = new AABB(-1, 2, -.2, 1, 2.16, .2);
        GroundLootPhysics.Step supported = GroundLootPhysics.sweep(overhang, new Vec3(0, -4, 0), List.of(edge));
        near(overhang.move(supported.movement()).minY, 1,
                "support below the muzzle counts even when the entity center is above air");
    }

    private static void manyTickDrops() {
        AABB floor = new AABB(-100, -1, -100, 100, 0, 100);
        AABB body = box(0, 16, 0, 1.2, .18, .3);
        Vec3 velocity = new Vec3(.1, .25, -.1);
        boolean landed = false;
        for (int tick = 0; tick < 160; tick++) {
            GroundLootPhysics.Step result = GroundLootPhysics.step(body, velocity, List.of(floor));
            body = body.move(result.movement());
            velocity = result.velocity();
            nonpenetrating(body, List.of(floor));
            if (result.grounded()) landed = true;
            if (landed) {
                near(body.minY, 0, "settled gravity checks remain exactly on the floor over many ticks");
                near(velocity.y, 0, "settled vertical velocity never restarts a bounce");
            }
        }
        check(landed, "an upward-thrown item eventually lands under gravity");
        GroundLootPhysics.Step removed = GroundLootPhysics.step(body, velocity, List.of());
        near(removed.movement().y, -.04, "long-resting items fall when support is destroyed");
    }

    private static void randomizedNonpenetration() {
        Random random = new Random(0x50425547);
        List<AABB> course = List.of(new AABB(-8, -.5, -8, 8, 0, 8),
                new AABB(2, 0, -8, 2.1, 6, 8), new AABB(-8, 0, 3, 8, 6, 3.1),
                new AABB(-8, 5, -8, 8, 5.1, 8), new AABB(-3, 0, -3, -1, .5, -1));
        for (int sample = 0; sample < 240; sample++) {
            AABB body = box(-.5 + random.nextDouble(), 1 + random.nextDouble() * 2,
                    -.5 + random.nextDouble(), .1 + random.nextDouble() * 1.5,
                    .02 + random.nextDouble() * .4, .1 + random.nextDouble());
            Vec3 desired = new Vec3((random.nextDouble() - .5) * 60,
                    (random.nextDouble() - .5) * 60, (random.nextDouble() - .5) * 60);
            GroundLootPhysics.Step ordered = GroundLootPhysics.sweep(body, desired, course);
            List<AABB> reverse = new ArrayList<>(course);
            Collections.reverse(reverse);
            GroundLootPhysics.Step reversed = GroundLootPhysics.sweep(body, desired, reverse);
            nonpenetrating(body.move(ordered.movement()), course);
            for (int travel = 1; travel < 10; travel++) {
                nonpenetrating(body.move(ordered.movement().scale(travel / 10.0)), course);
            }
            check(ordered.movement().distanceToSqr(reversed.movement()) < 1e-18,
                    "collision result does not depend on voxel-box enumeration order");
            check(ordered.velocity().distanceToSqr(reversed.velocity()) < 1e-18
                            && ordered.grounded() == reversed.grounded(),
                    "contact velocity and support normals are also independent of enumeration order");
            AABB broad = GroundLootPhysics.sweepBounds(body, desired);
            check(encloses(broad, body.move(ordered.movement())),
                    "the collision collection bounds contain every resolved endpoint");
        }
    }

    private static void invalidInputs() {
        AABB valid = box(0, 0, 0, .6, .15, .25);
        rejects(() -> GroundLootPhysics.step(valid, new Vec3(Double.NaN, 0, 0), List.of()),
                "NaN velocity cannot poison saved entity positions");
        rejects(() -> GroundLootPhysics.sweep(valid, new Vec3(0, Double.POSITIVE_INFINITY, 0), List.of()),
                "infinite movement is rejected before broad-phase access");
        rejects(() -> GroundLootPhysics.step(new AABB(0, 0, 0, 0, 1, 1), Vec3.ZERO, List.of()),
                "empty collision footprints are rejected");
        rejects(() -> GroundLootPhysics.step(valid, Vec3.ZERO, List.of(new AABB(0, 0, 0, 1, 1, Double.NaN))),
                "a malformed obstacle cannot silently disable gravity");
    }

    private static AABB box(double x, double bottom, double z, double width, double height, double depth) {
        return new AABB(x - width / 2, bottom, z - depth / 2, x + width / 2, bottom + height, z + depth / 2);
    }

    private static boolean encloses(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }

    private static void nonpenetrating(AABB body, List<AABB> obstacles) {
        for (AABB obstacle : obstacles) {
            check(!body.deflate(1e-8).intersects(obstacle.deflate(1e-8)),
                    "the resolved complete model box does not penetrate a collision shape");
        }
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-8, message + ": " + actual + " != " + expected);
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
