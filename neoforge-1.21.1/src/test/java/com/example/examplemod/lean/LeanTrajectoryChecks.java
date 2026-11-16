package com.example.examplemod.lean;

import com.example.examplemod.gunbalance.ballistics.BallisticTrajectory;
import java.util.Random;
import net.minecraft.world.phys.Vec3;

/** Same-base-origin translation contract; does not equate TaCZ's moving half-tick eye with the current eye. */
public final class LeanTrajectoryChecks {
    private static int checks;
    private LeanTrajectoryChecks() {}

    public static void main(String[] args) {
        Random random = new Random(2026111601L);
        for (int trial = 0; trial < 256; trial++) {
            double yaw = random.nextDouble() * Math.PI * 2;
            Vec3 offset = LeanGeometry.offset(yaw, random.nextDouble() * 2 - 1);
            Vec3 base = new Vec3(random.nextDouble() * 2000, 70 + random.nextDouble() * 50, random.nextDouble() * 2000);
            Vec3 velocity = new Vec3((random.nextDouble() - .5) * 60,
                    (random.nextDouble() - .5) * 20, (random.nextDouble() - .5) * 60);
            double gravity = .01 + random.nextDouble() * .04;
            double drag = random.nextDouble() * .003;
            nativeFlight(base, offset, velocity, gravity, .01 + random.nextDouble() * .04, trial % 2 == 0);
            experimentFlight(base, offset, velocity, gravity, drag);

            // A rotated gun/reticle never rotates the world's gravity vector.
            var noDrag = BallisticTrajectory.step(velocity.x, velocity.y, velocity.z, gravity, 0);
            near(noDrag.vx(), velocity.x, "gravity does not bend X with the tilted gun");
            near(noDrag.vz(), velocity.z, "gravity does not bend Z with the tilted gun");
            near(noDrag.vy(), velocity.y - gravity, "gravity remains world-down");
        }
        System.out.println("LeanTrajectoryChecks: " + checks + " checks across 32768 flight steps passed");
    }

    private static void experimentFlight(Vec3 base, Vec3 offset, Vec3 launch, double gravity, double drag) {
        Vec3 plain = base, leaned = base.add(offset), a = launch, b = launch;
        for (int tick = 0; tick < 64; tick++) {
            var normal = BallisticTrajectory.step(a.x, a.y, a.z, gravity, drag);
            var shifted = BallisticTrajectory.step(b.x, b.y, b.z, gravity, drag);
            Vec3 chordA = new Vec3(normal.dx(), normal.dy(), normal.dz());
            Vec3 chordB = new Vec3(shifted.dx(), shifted.dy(), shifted.dz());
            close(chordA, chordB, "experimental swept collision chord unchanged");
            plain = plain.add(chordA); leaned = leaned.add(chordB);
            a = new Vec3(normal.vx(), normal.vy(), normal.vz());
            b = new Vec3(shifted.vx(), shifted.vy(), shifted.vz());
            contract(base, offset, plain, leaned, a, b);
        }
    }

    /** TaCZ r7 reference order: sweep P→P+V, move by V, then apply drag and gravity. */
    private static void nativeFlight(Vec3 base, Vec3 offset, Vec3 launch, double gravity, double friction, boolean water) {
        Vec3 plain = base, leaned = base.add(offset), a = launch, b = launch;
        float nativeFriction = water ? .4F : (float) friction, nativeGravity = (float) gravity;
        if (water) nativeGravity *= .6F;
        double retention = 1 - nativeFriction, fall = nativeGravity;
        for (int tick = 0; tick < 64; tick++) {
            Vec3 hitEndA = plain.add(a), hitEndB = leaned.add(b);
            close(hitEndB.subtract(hitEndA), offset, "native collision segment remains translated");
            plain = hitEndA; leaned = hitEndB;
            a = a.scale(retention).add(0, -fall, 0);
            b = b.scale(retention).add(0, -fall, 0);
            contract(base, offset, plain, leaned, a, b);
        }
    }

    private static void contract(Vec3 base, Vec3 offset, Vec3 plain, Vec3 leaned, Vec3 a, Vec3 b) {
        close(leaned.subtract(plain), offset, "lean changes only the launch-origin translation");
        close(a, b, "actual velocity and drop progression unchanged");
        near(leaned.distanceTo(base.add(offset)), plain.distanceTo(base), "range measured from own startPos unchanged");
    }

    private static void close(Vec3 a, Vec3 b, String message) {
        near(a.x, b.x, message); near(a.y, b.y, message); near(a.z, b.z, message);
    }
    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 1e-8) throw new AssertionError(message + ": " + actual + " != " + expected);
        checks++;
    }
}
