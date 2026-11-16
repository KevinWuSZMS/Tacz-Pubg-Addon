package com.example.examplemod.lean;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
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
        decomposedWallMotion();
        mergedShapesKeepRealGaps();
        interpolatedEyeMotion();
        System.out.println("LeanCollisionChecks: " + checks + " directional body and hard eye collision checks passed; no game launched");
    }

    private static void decomposedWallMotion() {
        AABB solid = new AABB(-3, 0, 0, 3, 3, 1);
        var merged = List.of(solid);
        var tiled = wallGrid(new double[]{-3, -2, -1, 0, 1, 2, 3}, new double[]{0, 1, 2, 3});
        var shuffled = new ArrayList<>(tiled);
        Collections.shuffle(shuffled, new Random(41371));
        var variants = List.of(tiled, shuffled,
                wallGrid(new double[]{-3, -2, -1, 0, 1, 2, 3}, new double[]{0, 3}),
                wallGrid(new double[]{-3, 3}, new double[]{0, 1, 2, 3}),
                wallGrid(new double[]{-3, -.2, 0, .4, 3}, new double[]{0, .4, 1, 2, 3}));
        for (var variant : variants) {
            var result = LeanCollision.mergeFaces(variant);
            check(result.size() == 1 && result.getFirst().equals(solid), "equivalent solid wall tilings merge without depending on tile order or tile size");
        }
        double worstStep = 0;
        // These include the old 60-degree full-to-4% jump, and two 30-degree 1/24 jumps.
        for (double[] scene : new double[][]{{60, 0, 1}, {30, 0, -1}, {30, .5, -1}}) {
            for (double mirror : new double[]{-1, 1}) {
                double yaw = Math.toRadians(scene[0] * mirror), side = scene[2] * mirror;
                var pose = new LeanGeometry.Pose(yaw, yaw, 1.62, 1, false, 0);
                double previous = Double.NaN;
                for (int sample = 0; sample <= 350; sample++) {
                    double distance = .65 - sample * .001;
                    Vec3 feet = new Vec3(scene[1] * mirror, 0, -distance);
                    double reference = movingLimit(feet, pose, side, .06, merged);
                    double actual = movingLimit(feet, pose, side, .06, tiled);
                    close(actual, reference, 1.0e-7, "continuous wall-normal motion is independent of internal block seams");
                    if (sample > 0) {
                        double step = Math.abs(actual - previous);
                        worstStep = Math.max(worstStep, step);
                        check(step < .01, "one millimetre of normal motion must not cause a discrete body-budget jump");
                    }
                    previous = actual;
                }
                for (double distance : new double[]{.301, .302, .306, .307, .417, .418, .5, .65}) {
                    Vec3 feet = new Vec3(scene[1] * mirror, 0, -distance);
                    double reference = movingLimit(feet, pose, side, .06, merged);
                    for (var variant : variants) close(movingLimit(feet, pose, side, .06, variant), reference, 1.0e-7,
                            "known seam regressions remain invariant under alternate wall decompositions");
                }
            }
        }
        System.out.printf("Wall-normal trajectory: maximum fraction step over 1 mm = %.9f%n", worstStep);
    }

    private static List<AABB> wallGrid(double[] xs, double[] ys) {
        var result = new ArrayList<AABB>();
        for (int x = 1; x < xs.length; x++) for (int y = 1; y < ys.length; y++)
            result.add(new AABB(xs[x - 1], ys[y - 1], 0, xs[x], ys[y], 1));
        return result;
    }

    private static void mergedShapesKeepRealGaps() {
        var narrowGap = LeanCollision.mergeFaces(List.of(new AABB(-1, 0, 0, 0, 1, 1), new AABB(.0001, 0, 0, 1, 1, 1)));
        check(narrowGap.size() == 2 && !contains(narrowGap, new Vec3(.00005, .5, .5)), "merging preserves a real gap narrower than the sweep margin");
        var corner = LeanCollision.mergeFaces(List.of(new AABB(0, 0, 0, 2, 1, 1), new AABB(0, 1, 0, 1, 2, 1)));
        check(!contains(corner, new Vec3(1.5, 1.5, .5)) && contains(corner, new Vec3(.5, 1.5, .5)) && contains(corner, new Vec3(1.5, .5, .5)),
                "an L-shaped union keeps its empty corner and both occupied arms");
        var stepped = LeanCollision.mergeFaces(List.of(new AABB(0, 0, 0, 1, 1, 1), new AABB(1, 0, 0, 2, 1, .5)));
        check(!contains(stepped, new Vec3(1.5, .5, .75)) && contains(stepped, new Vec3(1.5, .5, .25)), "different wall thicknesses do not fill a step recess");
        var thinBoards = LeanCollision.mergeFaces(List.of(new AABB(0, 0, 0, 1, 1, .001), new AABB(0, 0, .0011, 1, 1, .002)));
        check(!contains(thinBoards, new Vec3(.5, .5, .00105)) && contains(thinBoards, new Vec3(.5, .5, .0005)), "thin parallel boards retain the space between them");
        var overlap = LeanCollision.mergeFaces(List.of(new AABB(0, 0, 0, 1, 1, 1), new AABB(.5, 0, 0, 2, 1, 1)));
        check(overlap.size() == 1 && overlap.getFirst().equals(new AABB(0, 0, 0, 2, 1, 1)), "collinear overlap merges to exactly its occupied union");
    }

    private static boolean contains(List<AABB> boxes, Vec3 point) { return boxes.stream().anyMatch(box -> box.contains(point)); }

    private static void interpolatedEyeMotion() {
        var walls = List.of(new AABB(-3, 0, 0, 3, 3, 1));
        double worstOldJump = 0, worstNewJump = 0;
        for (double mirror : new double[]{-1, 1}) for (double velocity : new double[]{-.01, .01}) {
            double yaw = Math.toRadians(60 * mirror), side = -mirror;
            var pose = new LeanGeometry.Pose(yaw, yaw, 1.62, 1, false, 0);
            double initial = velocity > 0 ? -.37 : -.31;
            Vec3 oldPrevious = null, newPrevious = null;
            for (int tick = 0; tick < 6; tick++) {
                Vec3 before = new Vec3(0, 0, initial + velocity * tick);
                Vec3 actual = before.add(0, 0, velocity);
                double actualFraction = movingLimit(actual, pose, side, .4, walls);
                for (int frame = 0; frame <= 20; frame++) {
                    Vec3 rendered = before.lerp(actual, frame / 20.0);
                    Vec3 oldEye = rendered.add(0, pose.eyeHeight(), 0).add(LeanGeometry.offset(pose, actualFraction));
                    double renderFraction = movingLimit(rendered, pose, side, .4, walls);
                    Vec3 newEye = rendered.add(0, pose.eyeHeight(), 0).add(LeanGeometry.offset(pose, renderFraction));
                    check(walls.stream().noneMatch(wall -> new AABB(newEye, newEye).inflate(.08).intersects(wall)),
                            "render-position clamp keeps the displayed eye outside the wall on approach and retreat");
                    if (oldPrevious != null && frame == 0) {
                        worstOldJump = Math.max(worstOldJump, Math.abs(oldEye.z - oldPrevious.z));
                        worstNewJump = Math.max(worstNewJump, Math.abs(newEye.z - newPrevious.z));
                        close(newEye.distanceTo(newPrevious), 0, 1.0e-7, "identical rendered feet on either side of a tick boundary produce an identical displayed eye");
                    }
                    oldPrevious = oldEye; newPrevious = newEye;
                }
            }
        }
        check(worstOldJump > .008, "the previous actual-tick clamp plus interpolated position reproduces the wall-normal camera jump");
        check(worstNewJump < 1.0e-7, "clamping at the same rendered position removes the tick-boundary camera jump");
        System.out.printf("Tick-boundary normal eye jump: old mixed positions=%.9f, consistent rendered position=%.9f%n", worstOldJump, worstNewJump);
    }

    private static double movingLimit(Vec3 feet, LeanGeometry.Pose pose, double side, double tolerance, List<AABB> walls) {
        return LeanGeometry.clamp(feet, pose, movement(pose.scale()).move(feet), side, tolerance, walls);
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
