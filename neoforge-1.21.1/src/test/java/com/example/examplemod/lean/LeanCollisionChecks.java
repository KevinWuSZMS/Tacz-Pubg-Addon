package com.example.examplemod.lean;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure wall-adjacent lean checks: body allowances never relax the eye's swept collision. */
public final class LeanCollisionChecks {
    private static int checks;

    public static void main(String[] args) {
        for (double scale : new double[]{.7, 1, 1.4}) {
            awayFromSideWalls(scale);
            parallelToFrontWalls(scale);
            towardWallKeepsEyesOutside(scale);
            thinWallAlongEyeArc(scale);
            fixedBodyPenetrationBudget(scale);
            crouchedAndProneWalls(scale);
            diagonalCornerWalls(scale);
            orientedBodyEmptyCorner(scale);
            unobstructedLean(scale);
        }
        System.out.println("LeanCollisionChecks: " + checks + " directional body and hard eye collision checks passed; no game launched");
    }

    private static void awayFromSideWalls(double scale) {
        var pose = standing(scale, 0, 0);
        AABB rightWall = scaled(-1, 0, -.5, -.30, 2, .5, scale);
        AABB leftWall = scaled(.30, 0, -.5, 1, 2, .5, scale);
        full(clamp(pose, -1, .06 * scale, rightWall), -1,
                "right shoulder already touches the wall but full left lean moves the eye away");
        full(clamp(pose, 1, .06 * scale, leftWall), 1,
                "mirrored left-wall contact allows full right lean");
    }

    private static void parallelToFrontWalls(double scale) {
        AABB wall = scaled(-2, 0, .30, 2, 3, 1, scale);
        var lookingDown = standing(scale, 0, Math.toRadians(20));
        var turnedBody = standing(scale, Math.PI / 4, 0);
        for (var pose : List.of(lookingDown, turnedBody)) {
            var neutral = LeanGeometry.frame(Vec3.ZERO, pose, 0);
            check(neutral.boxes().stream().skip(1).anyMatch(box -> box.bounds().intersects(wall)),
                    "frontal fixture begins with an existing head or shoulder overlap");
            for (double direction : new double[]{-1, 1}) {
                full(clamp(pose, direction, .06 * scale, wall), direction,
                        "both lean directions remain available while facing a wall with existing body overlap");
                full(clamp(pose, direction, 0, wall), direction,
                        "zero tolerance still permits tangential motion with unchanged wall-normal depth");
            }
        }
    }

    private static void towardWallKeepsEyesOutside(double scale) {
        var pose = standing(scale, 0, 0);
        AABB right = scaled(-1, 0, -.5, -.30, 2, .5, scale);
        AABB left = scaled(.30, 0, -.5, 1, 2, .5, scale);
        double rightLimit = clamp(pose, 1, .4 * scale, right);
        double leftLimit = clamp(pose, -1, .4 * scale, left);
        check(rightLimit > .5 && rightLimit < .8, "large body allowance still stops a right lean at the eye sweep");
        check(leftLimit < -.5 && leftLimit > -.8, "large body allowance still stops a left lean at the eye sweep");
        close(rightLimit, -leftLimit, 1.0e-4, "mirrored walls have mirrored eye limits");
        check(eye(pose, rightLimit).x - .08 * scale >= right.maxX - 1.0e-7,
                "right eye volume never enters the wall even with permissive body overlap");
        check(eye(pose, leftLimit).x + .08 * scale <= left.minX + 1.0e-7,
                "left eye volume never enters the wall even with permissive body overlap");
    }

    private static void thinWallAlongEyeArc(double scale) {
        var pose = standing(scale, 0, 0);
        // The physical player is outside this thin slab. The final eye has dropped below it,
        // but the eye volume crosses it during the arc: endpoint-only checks would miss it.
        AABB thin = scaled(-.32, 1.55, -.5, -.3199, 2, .5, scale);
        check(!movement(scale).intersects(thin), "thin-wall fixture does not start inside the movement box");
        check(!eyeBox(pose, 0).intersects(thin) && !eyeBox(pose, 1).intersects(thin),
                "both complete eye endpoints are clear of the thin wall");
        check(eyeBox(pose, .75).intersects(thin), "the intermediate eye arc crosses the thin wall");
        double limit = clamp(pose, 1, .4 * scale, thin);
        check(limit > .5 && limit < .75, "continuous eye sweep rejects the thin obstacle between clear endpoints");
        check(!eyeBox(pose, limit).intersects(thin), "clamped eye remains outside the thin slab");
    }

    private static void fixedBodyPenetrationBudget(double scale) {
        var pose = standing(scale, 0, 0);
        AABB wall = scaled(-1, 0, -.5, -.30, 2, .5, scale);
        double tolerance = .015 * scale;
        var neutralArm = LeanGeometry.frame(Vec3.ZERO, pose, 0).boxes().get(4);
        double baseline = wall.maxX - neutralArm.bounds().minX;
        double limit = clamp(pose, 1, tolerance, wall);
        var clampedArm = LeanGeometry.frame(Vec3.ZERO, pose, limit).boxes().get(4);
        double depth = wall.maxX - clampedArm.bounds().minX;
        check(limit > 0 && limit < .5, "new body penetration beyond the small fixed budget is clamped before the eye reaches the wall");
        check(depth <= baseline + tolerance + 1.0e-6 * scale,
                "allowed penetration is relative to the neutral body, not accumulated along sweep steps");
        check(wall.maxX - LeanGeometry.frame(Vec3.ZERO, pose, 1).boxes().get(4).bounds().minX > baseline + tolerance,
                "fixture's full lean genuinely exceeds the extra penetration budget");
        check(clamp(pose, 1, .06 * scale, wall) > limit,
                "a larger body allowance permits additional lean without removing collision limits");
    }

    private static void crouchedAndProneWalls(double scale) {
        AABB rightWall = scaled(-1, 0, -.5, -.30, 2, .5, scale);
        AABB leftWall = scaled(.30, 0, -.5, 1, 2, .5, scale);
        AABB frontWall = scaled(-2, 0, .30, 2, 3, 1, scale);
        for (boolean prone : new boolean[]{false, true}) {
            var pose = new LeanGeometry.Pose(0, 0, (prone ? .4 : 1.27) * scale, scale, prone,
                    prone ? -Math.PI / 4 : Math.toRadians(20));
            AABB movement = scaled(-.3, 0, -.3, .3, prone ? .6 : 1.5, .3, scale);
            String stance = prone ? "prone" : "crouched";
            for (int side = 0; side < 2; side++) {
                AABB wall = side == 0 ? rightWall : leftWall;
                double requested = side == 0 ? -1 : 1;
                double limit = LeanGeometry.clamp(Vec3.ZERO, pose, movement, requested, .06 * scale, List.of(wall));
                check(limit * requested >= .5, stance + " player can meaningfully lean away from either adjacent side wall");
                clearEyePath(pose, limit, List.of(wall), stance + " side-wall escape");
            }
            for (double requested : new double[]{-1, 1}) {
                double limit = LeanGeometry.clamp(Vec3.ZERO, pose, movement, requested, .06 * scale, List.of(frontWall));
                check(limit * requested >= .5, stance + " player retains both tangential lean directions while facing a wall");
                clearEyePath(pose, limit, List.of(frontWall), stance + " frontal-wall slide");
            }
        }
    }

    private static void diagonalCornerWalls(double scale) {
        var pose = new LeanGeometry.Pose(Math.PI / 4, Math.PI / 4, 1.62 * scale, scale, false, 0);
        var walls = List.of(scaled(-1, 0, -2, -.30, 3, 2, scale), scaled(-2, 0, .30, 2, 3, 1, scale));
        for (double requested : new double[]{-1, 1}) {
            double limit = LeanGeometry.clamp(Vec3.ZERO, pose, movement(scale), requested, .4 * scale, walls);
            check(limit * requested > .5 && limit * requested < 1,
                    "diagonal corner lean is limited by either wall even with a large body allowance");
            clearEyePath(pose, limit, walls, "diagonal L-shaped corner");
            check(walls.stream().anyMatch(wall -> eyeBox(pose, requested).intersects(wall)),
                    "full diagonal lean would genuinely enter one of the two corner walls");
        }
    }

    private static void orientedBodyEmptyCorner(double scale) {
        var pose = new LeanGeometry.Pose(Math.PI / 4, Math.PI / 4, 1.62 * scale, scale, false, 0);
        AABB pillar = scaled(.40, 1.0, .40, .45, 1.1, .45, scale);
        var neutral = LeanGeometry.frame(Vec3.ZERO, pose, 0);
        check(neutral.boxes().stream().skip(1).anyMatch(box -> box.bounds().intersects(pillar)),
                "diagonal small-pillar fixture intersects a coarse body AABB");
        check(neutral.boxes().stream().skip(1).noneMatch(box -> LeanCollision.intersects(box, pillar)),
                "the same pillar occupies an empty OBB corner, not pre-existing body penetration");
        full(LeanGeometry.clamp(Vec3.ZERO, pose, movement(scale), 1, 0, List.of(pillar)), 1,
                "leaning away from an empty coarse-box corner is not falsely rejected");
        double limit = LeanGeometry.clamp(Vec3.ZERO, pose, movement(scale), -1, 0, List.of(pillar));
        check(limit < 0 && limit > -1, "leaning into the corner cannot inherit penetration from a false AABB overlap");
        var stopped = LeanGeometry.frame(Vec3.ZERO, pose, limit);
        check(stopped.boxes().stream().skip(1).noneMatch(box -> LeanCollision.intersects(box, pillar)),
                "zero body allowance stops before any actual OBB penetration into the new pillar");
        clearEyePath(pose, limit, List.of(pillar), "oriented empty-corner fixture");
    }

    private static void clearEyePath(LeanGeometry.Pose pose, double limit, List<AABB> walls, String label) {
        for (int sample = 0; sample <= 32; sample++) {
            AABB eye = eyeBox(pose, limit * sample / 32);
            check(walls.stream().noneMatch(eye::intersects), label + " keeps every sampled eye volume clear, including its initial position");
        }
    }

    private static void unobstructedLean(double scale) {
        var pose = standing(scale, .7, .2);
        for (double fraction : new double[]{-1, -.37, 0, .37, 1}) {
            full(LeanGeometry.clamp(Vec3.ZERO, pose, movement(scale), fraction, 0, List.of()), fraction,
                    "empty space preserves the exact requested lean");
        }
    }

    private static double clamp(LeanGeometry.Pose pose, double fraction, double tolerance, AABB wall) {
        return LeanGeometry.clamp(Vec3.ZERO, pose, movement(pose.scale()), fraction, tolerance, List.of(wall));
    }

    private static LeanGeometry.Pose standing(double scale, double bodyYaw, double pitch) {
        return new LeanGeometry.Pose(0, bodyYaw, 1.62 * scale, scale, false, pitch);
    }

    private static AABB movement(double scale) { return scaled(-.3, 0, -.3, .3, 1.8, .3, scale); }

    private static AABB scaled(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double scale) {
        return new AABB(minX * scale, minY * scale, minZ * scale, maxX * scale, maxY * scale, maxZ * scale);
    }

    private static Vec3 eye(LeanGeometry.Pose pose, double fraction) {
        return new Vec3(0, pose.eyeHeight(), 0).add(LeanGeometry.offset(pose, fraction));
    }

    private static AABB eyeBox(LeanGeometry.Pose pose, double fraction) {
        Vec3 eye = eye(pose, fraction);
        return new AABB(eye, eye).inflate(.08 * pose.scale());
    }

    private static void full(double actual, double expected, String label) { close(actual, expected, 1.0e-7, label); }

    private static void close(double actual, double expected, double epsilon, String label) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= epsilon, label + ": " + actual + " != " + expected);
    }

    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
