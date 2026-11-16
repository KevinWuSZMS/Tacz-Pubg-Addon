package com.example.examplemod.lean.client;

import com.example.examplemod.lean.LeanGeometry;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure stance/camera transition checks, including interrupted animation and swept cover constraints. */
public final class LeanViewTransitionChecks {
    private static int checks;
    private static final double EPSILON = 1.0e-8;

    public static void main(String[] args) {
        everyDirectedTransition();
        interruptedTransitions();
        frameRateIndependence();
        liveAimAndLean();
        resetAndTimeRollback();
        sweptObstacles();
        sweptBoundaryContact();
        System.out.println("LeanViewTransitionChecks: " + checks + " view transition and sweep checks passed; no game launched");
    }

    private static void everyDirectedTransition() {
        for (double scale : new double[]{.7, 1, 1.4}) {
            var eyes = eyes(scale);
            for (int from = 0; from < 3; from++) {
                for (int to = 0; to < 3; to++) {
                    if (from == to) continue;
                    for (double lean : new double[]{-1, 1}) {
                        LeanViewTransition transition = new LeanViewTransition();
                        var start = pose(from, scale, .91, -.37);
                        var target = pose(to, scale, .91, -.37);
                        Vec3 a = LeanGeometry.offset(start, lean), b = LeanGeometry.offset(target, lean);
                        close(transition.offset(start, eyes, lean, 0), a, "first observation uses its actual stance without startup animation");
                        close(transition.offset(target, eyes, lean, 10), a, "changing stance preserves the previous offset at the switch time");
                        double duration = LeanViewTransition.TRAVEL_TICKS;
                        for (double progress : new double[]{.0001, .25, .5, .75, .9999, 1}) {
                            double eased = progress * progress * (3 - 2 * progress);
                            Vec3 expected = blend(a, b, eased);
                            close(transition.offset(target, eyes, lean, 10 + duration * progress), expected,
                                    "all directed stance transitions follow continuous smoothstep weights");
                        }
                        close(transition.offset(target, eyes, lean, 100), b, "settled endpoint equals the original rig at every scale and side");
                    }
                }
            }
        }
    }

    private static void interruptedTransitions() {
        for (double lean : new double[]{-1, 1}) {
            var eyes = eyes(1);
            var stand = pose(0, 1, .4, -.8);
            var crouch = pose(1, 1, .4, -.8);
            var prone = pose(2, 1, .4, -.8);
            LeanViewTransition transition = new LeanViewTransition();
            transition.offset(stand, eyes, lean, 0);
            transition.offset(prone, eyes, lean, 10);
            Vec3 interrupted = transition.offset(prone, eyes, lean, 11);
            close(transition.offset(stand, eyes, lean, 11), interrupted, "quick reversal continues from the displayed weights");
            Vec3 reverseMiddle = transition.offset(stand, eyes, lean, 11 + LeanViewTransition.TRAVEL_TICKS / 2);
            close(reverseMiddle, blend(interrupted, LeanGeometry.offset(stand, lean), .5), "reverse transition has no reset to a complete prior stance");
            close(transition.offset(crouch, eyes, lean, 13), reverseMiddle, "switching again to the third stance preserves the current mixture");
            close(transition.offset(crouch, eyes, lean, 13 + LeanViewTransition.TRAVEL_TICKS), LeanGeometry.offset(crouch, lean),
                    "interrupted three-stance transition reaches the exact last requested endpoint");
        }
    }

    private static void frameRateIndependence() {
        Vec3 reference = sampleAtFrameRate(30);
        close(sampleAtFrameRate(60), reference, "60 FPS sampling agrees with 30 FPS at identical game time");
        close(sampleAtFrameRate(144), reference, "144 FPS sampling agrees with 30 FPS at identical game time");
        LeanViewTransition transition = new LeanViewTransition();
        var eyes = eyes(1);
        transition.offset(pose(0, 1, 1, .3), eyes, 1, 0);
        var target = pose(2, 1, 1, .3);
        transition.offset(target, eyes, 1, 10);
        Vec3 first = transition.offset(target, eyes, 1, 11.7);
        for (int call = 0; call < 5; call++) {
            close(transition.offset(target, eyes, 1, 11.7), first, "repeated same-frame samples do not advance the transition");
        }
    }

    private static Vec3 sampleAtFrameRate(int fps) {
        LeanViewTransition transition = new LeanViewTransition();
        var eyes = eyes(1);
        transition.offset(pose(0, 1, -.7, .6), eyes, -.8, 0);
        var target = pose(2, 1, -.7, .6);
        transition.offset(target, eyes, -.8, 10);
        for (double tick = 10 + 20.0 / fps; tick < 12.7; tick += 20.0 / fps) transition.offset(target, eyes, -.8, tick);
        return transition.offset(target, eyes, -.8, 12.7);
    }

    private static void liveAimAndLean() {
        LeanViewTransition transition = new LeanViewTransition();
        var eyes = eyes(1);
        transition.offset(pose(0, 1, 0, 0), eyes, 1, 0);
        transition.offset(pose(2, 1, 0, 0), eyes, 1, 10);
        double middle = 10 + LeanViewTransition.TRAVEL_TICKS / 2;
        Vec3 originalDirection = transition.offset(pose(2, 1, 0, 0), eyes, 1, middle);
        var turned = pose(2, 1, 1.2, -.65);
        Vec3 expected = blend(LeanGeometry.offset(pose(0, 1, 1.2, -.65), 1), LeanGeometry.offset(turned, 1), .5);
        Vec3 actual = transition.offset(turned, eyes, 1, middle);
        close(actual, expected, "view/body yaw update immediately without restarting stance weights");
        check(actual.distanceTo(originalDirection) > .01, "turning the view changes the rendered displacement immediately");
        for (double fraction : new double[]{.25, -.5, 0}) {
            Vec3 sameFrame = transition.offset(turned, eyes, fraction, middle);
            close(sameFrame, blend(LeanGeometry.offset(pose(0, 1, 1.2, -.65), fraction), LeanGeometry.offset(turned, fraction), .5),
                    "legal lean fraction takes effect immediately during a stance transition");
            if (fraction == 0) check(sameFrame.x == 0 && sameFrame.y == 0 && sameFrame.z == 0, "zero lean is strictly zero with no smoothing residual");
        }
        close(transition.offset(turned, eyes, 1, middle), expected, "releasing and restoring lean does not restart the stance transition");
    }

    private static void resetAndTimeRollback() {
        LeanViewTransition transition = new LeanViewTransition();
        var eyes = eyes(1);
        var stand = pose(0, 1, .8, -.4);
        var prone = pose(2, 1, .8, -.4);
        transition.offset(stand, eyes, 1, 20);
        transition.offset(prone, eyes, 1, 21);
        transition.offset(prone, eyes, 1, 22);
        close(transition.offset(prone, eyes, 1, 0), LeanGeometry.offset(prone, 1), "time rollback starts at the new current stance instead of carrying stale state");
        transition.offset(stand, eyes, 1, 1);
        transition.reset();
        close(transition.offset(stand, eyes, -1, 2), LeanGeometry.offset(stand, -1), "explicit lifecycle reset removes old posture history");
    }

    private static void sweptObstacles() {
        Vec3 from = new Vec3(0, .5, .5), to = new Vec3(10, .5, .5);
        AABB thin = new AABB(4, 0, 0, 4.0001, 1, 1);
        scalar(LeanViewTransition.sweepFraction(from, to, List.of(), .08), 1, "an unobstructed transition reaches its endpoint");
        contact(LeanViewTransition.sweepFraction(from, to, List.of(thin), 0), .4, "sweep catches a wall far thinner than a transition step");
        contact(LeanViewTransition.sweepFraction(from, to, List.of(thin), .1), .39, "sweep includes the eye radius before wall contact");
        contact(LeanViewTransition.sweepFraction(to, from, List.of(thin), 0), (10 - thin.maxX) / 10, "reverse sweep catches the other side of a thin wall");
        scalar(LeanViewTransition.sweepFraction(new Vec3(4.00005, .5, .5), to, List.of(thin), 0), 0, "a sweep beginning inside an obstacle cannot move through it");
        scalar(LeanViewTransition.sweepFraction(new Vec3(3.95, .5, .5), to, List.of(thin), .1), 0, "starting inside the inflated eye obstacle returns zero");
        scalar(LeanViewTransition.sweepFraction(new Vec3(0, 2, .5), new Vec3(10, 2, .5), List.of(thin), .08), 1, "parallel coordinates outside the slab do not block movement");
        scalar(LeanViewTransition.sweepFraction(from, from, List.of(thin), .08), 1, "zero-length clear sweep stays finite and unobstructed");
        AABB later = new AABB(7, 0, 0, 8, 1, 1);
        contact(LeanViewTransition.sweepFraction(from, to, List.of(later, thin), 0), .4, "nearest contact wins regardless of obstacle order");
        AABB vertical = new AABB(-1, 2, -1, 1, 2.0001, 1);
        contact(LeanViewTransition.sweepFraction(Vec3.ZERO, new Vec3(0, 4, 0), List.of(vertical), .08), .48,
                "vertical stance transition sweeps through a thin ceiling");
    }

    private static void sweptBoundaryContact() {
        AABB wall = new AABB(0, 0, 0, 1, 1, 1);
        for (double radius : new double[]{0, .08}) {
            for (boolean positive : new boolean[]{false, true}) {
                double face = positive ? 1 + radius : -radius;
                double outward = positive ? 1 : -1;
                Vec3 contact = new Vec3(face, .5, .5);
                scalar(LeanViewTransition.sweepFraction(contact, contact.add(outward * .2, 0, 0), List.of(wall), radius),
                        1, "a sweep touching either inflated face may retreat from the wall");
                scalar(LeanViewTransition.sweepFraction(contact, contact.add(0, .2, 0), List.of(wall), radius),
                        1, "a sweep touching either inflated face may slide along the wall");
                scalar(LeanViewTransition.sweepFraction(contact, contact, List.of(wall), radius),
                        1, "stationary boundary contact does not count as interior overlap");
                scalar(LeanViewTransition.sweepFraction(contact, contact.add(-outward * .2, 0, 0), List.of(wall), radius),
                        0, "a sweep touching either inflated face cannot enter the wall");
                scalar(LeanViewTransition.sweepFraction(contact, contact.add(-outward * 5.0e-13, 0, 0), List.of(wall), radius),
                        0, "even a sub-picoblock movement into a touching face remains blocked");
                Vec3 inside = contact.add(-outward * .01, 0, 0);
                scalar(LeanViewTransition.sweepFraction(inside, contact.add(outward * .2, 0, 0), List.of(wall), radius),
                        0, "a strictly interior start remains blocked even when the endpoint is outside");
                scalar(LeanViewTransition.sweepFraction(inside, inside, List.of(wall), radius),
                        0, "stationary strictly interior overlap remains blocked");
            }
            double lower = -radius;
            Vec3 beforeCorner = new Vec3(lower - 1, lower + 1, .5);
            Vec3 afterCorner = new Vec3(lower + 1, lower - 1, .5);
            scalar(LeanViewTransition.sweepFraction(beforeCorner, afterCorner, List.of(wall), radius),
                    1, "a segment grazing only an inflated corner does not enter the wall");
            scalar(LeanViewTransition.sweepFraction(afterCorner, beforeCorner, List.of(wall), radius),
                    1, "corner grazing is unobstructed in either direction");
            scalar(LeanViewTransition.sweepFraction(new Vec3(lower, lower, .5), new Vec3(.5, .5, .5), List.of(wall), radius),
                    0, "a sweep from an inflated corner into the interior remains blocked");
        }
    }

    private static LeanViewTransition.Eyes eyes(double scale) {
        return new LeanViewTransition.Eyes(1.62 * scale, 1.27 * scale, .4 * scale);
    }

    private static LeanGeometry.Pose pose(int stance, double scale, double viewYaw, double bodyYaw) {
        double height = switch (stance) { case 0 -> 1.62; case 1 -> 1.27; case 2 -> .4; default -> throw new AssertionError(stance); };
        return new LeanGeometry.Pose(viewYaw, bodyYaw, height * scale, scale, stance == 2, stance == 2 ? -Math.PI / 4 : .3);
    }

    private static Vec3 blend(Vec3 from, Vec3 to, double fraction) { return from.scale(1 - fraction).add(to.scale(fraction)); }

    private static void contact(double actual, double expected, String label) {
        check(Double.isFinite(actual) && actual <= expected + EPSILON && actual >= expected - .00001,
                label + ": " + actual + " versus first contact " + expected);
    }

    private static void scalar(double actual, double expected, String label) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= EPSILON, label + ": " + actual + " != " + expected);
    }

    private static void close(Vec3 actual, Vec3 expected, String label) {
        check(Double.isFinite(actual.x) && Double.isFinite(actual.y) && Double.isFinite(actual.z)
                && actual.distanceTo(expected) <= EPSILON, label + ": " + actual + " != " + expected);
    }

    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
