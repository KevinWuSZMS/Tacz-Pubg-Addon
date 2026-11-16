package com.example.examplemod.lean;

import java.util.Random;
import net.minecraft.world.phys.Vec3;

/** Combat-space geometry checks; no renderer, registry bootstrap or live world required. */
public final class LeanGeometryChecks {
    private static int checks;
    private LeanGeometryChecks() { }
    public static void main(String[] args) {
        close(LeanGeometry.offset(0, 1), new Vec3(-.35, -.10, 0), "E at south-facing yaw moves screen-right");
        close(LeanGeometry.offset(0, -1), new Vec3(.35, -.10, 0), "Q mirrors horizontal displacement");
        close(LeanGeometry.offset(Math.PI / 2, 1), new Vec3(0, -.10, -.35), "right follows yaw at west-facing heading");
        close(LeanGeometry.offset(0, 5), LeanGeometry.offset(0, 1), "fraction bounded to authored displacement");
        close(LeanGeometry.offset(0, Double.NaN), Vec3.ZERO, "non-finite input has no displacement");
        var standing = LeanGeometry.frame(Vec3.ZERO, 0, 0, 1.62, 1, false, 1);
        var head = standing.boxes().stream().filter(LeanGeometry.Box::head).findFirst().orElseThrow();
        var headHit = standing.intersect(head.center().add(0, 0, -3), head.center().add(0, 0, 3)).orElseThrow();
        check(headHit.head(), "exposed head hit carries the head tag");
        var chest = standing.boxes().get(2);
        check(!standing.intersect(chest.center().add(0, 0, -3), chest.center().add(0, 0, 3)).orElseThrow().head(), "chest hit remains body damage");
        check(standing.intersect(new Vec3(.28, 1.62, -2), new Vec3(.28, 1.62, 2)).isEmpty(), "old upper silhouette is not also a ghost hitbox");
        var prone = LeanGeometry.frame(Vec3.ZERO, 0, 0, .4, 1, true, 1);
        var proneHead = prone.boxes().stream().filter(LeanGeometry.Box::head).findFirst().orElseThrow();
        double raisedHead = .234375 * Math.sqrt(.5);
        close(proneHead.center(), new Vec3(-.35, .2 + raisedHead, .4071875 + raisedHead), "prone head includes vanilla's raised 45 degree neck pose");
        var lookDown = LeanGeometry.frame(Vec3.ZERO, 0, 0, 1.62, 1, false, 0, Math.PI / 2).boxes().get(1);
        close(lookDown.center(), new Vec3(0, 1.4071875, .234375), "looking down rotates the head around its neck");
        var lookUp = LeanGeometry.frame(Vec3.ZERO, Math.PI / 2, 0, 1.62, 1, false, 0, -Math.PI / 2).boxes().get(1);
        close(lookUp.center(), new Vec3(.234375, 1.4071875, 0), "head yaw and pitch compose before world lean");
        close(prone.boxes().getFirst().center(), LeanGeometry.frame(Vec3.ZERO, 0, 0, .4, 1, true, -1).boxes().getFirst().center(), "prone legs do not follow upper body lean");
        check(prone.bounds().minZ < -.9 && prone.bounds().maxZ > .8, "prone broad phase spans the full horizontal body");
        var rotated = new LeanGeometry.Box(Vec3.ZERO, new Vec3(Math.sqrt(.5), 0, Math.sqrt(.5)),
                new Vec3(0, 1, 0), new Vec3(-Math.sqrt(.5), 0, Math.sqrt(.5)), new Vec3(1, 1, .1), true);
        check(rotated.bounds().contains(new Vec3(.7, 0, -.7)), "coarse oriented bounds include empty corners");
        check(rotated.intersect(new Vec3(.7, -2, -.7), new Vec3(.7, 2, -.7)).isEmpty(), "OBB precision rejects the coarse-box empty corner");
        check(rotated.intersect(Vec3.ZERO, Vec3.ZERO).isPresent(), "zero-length segment inside a volume is a hit");
        check(rotated.intersect(new Vec3(5, 0, 0), new Vec3(5, 0, 0)).isEmpty(), "zero-length segment outside has no hit");
        double limit = LeanGeometry.clampPath(1, (from, to) -> !(from < .401 && to > .400));
        check(limit <= .400 && limit > .399, "thin mid-sweep obstacle clamps before contact even when destination is clear");
        double leftLimit = LeanGeometry.clampPath(-1, (from, to) -> !(from > -.401 && to < -.400));
        check(leftLimit >= -.400 && leftLimit < -.399, "left contact refines with the same signed-distance rule");
        check(LeanGeometry.clampPath(.37, (from, to) -> true) == .37, "clear sweep preserves exact requested progress");
        Random random = new Random(751911);
        for (int index = 0; index < 200; index++) {
            double yaw = random.nextDouble() * 2 * Math.PI, bodyYaw = random.nextDouble() * 2 * Math.PI;
            double fraction = random.nextDouble() * 2 - 1;
            Vec3 feet = new Vec3(random.nextDouble() * 100, random.nextDouble() * 100, random.nextDouble() * 100);
            var frame = LeanGeometry.frame(feet, yaw, bodyYaw, index % 2 == 0 ? 1.62 : 1.27, 1, index % 3 == 0, fraction,
                    random.nextDouble() * Math.PI - Math.PI / 2);
            for (var box : frame.boxes()) {
                check(Math.abs(box.right().dot(box.up())) < 1e-8 && Math.abs(box.right().dot(box.forward())) < 1e-8
                        && Math.abs(box.up().dot(box.forward())) < 1e-8, "rig axes stay orthogonal with independent look/body yaw");
                check(box.intersect(box.center().subtract(box.forward().scale(2)), box.center().add(box.forward().scale(2))).isPresent(), "center-crossing ray hits each oriented body part");
                for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
                    Vec3 corner = box.center().add(box.right().scale(x * box.half().x))
                            .add(box.up().scale(y * box.half().y)).add(box.forward().scale(z * box.half().z));
                    check(frame.bounds().inflate(1e-7).contains(corner), "broad phase contains every oriented corner");
                }
            }
        }
        System.out.println("Lean geometry checks passed: " + checks + "; no world or game launched.");
    }
    private static void close(Vec3 actual, Vec3 expected, String message) { check(actual.distanceToSqr(expected) < 1e-12, message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
