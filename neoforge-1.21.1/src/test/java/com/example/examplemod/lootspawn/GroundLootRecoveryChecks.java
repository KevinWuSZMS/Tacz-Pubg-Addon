package com.example.examplemod.lootspawn;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Initial-overlap recovery must preserve data in place rather than teleport through walls. */
public final class GroundLootRecoveryChecks {
    private static int checks;
    public static void main(String[] args) {
        AABB wall = new AABB(.8, -1, -3, 2, 3, 3);
        AABB body = new AABB(-1, 0, -.2, 1, .1, .2);
        Vec3 away = GroundLootWorldPhysics.recovery(body, Vec3.ZERO, List.of(wall));
        check(away != null && away.x < 0 && away.y == 0 && away.z == 0, "a long muzzle moves away from its nearby wall");
        check(!body.move(away).intersects(wall), "the recovered complete model is free of the wall");

        AABB floor = new AABB(-3, -1, -3, 3, 0, 3);
        AABB slightlyLow = new AABB(-.2, -.01, -.2, .2, .1, .2);
        Vec3 up = GroundLootWorldPhysics.recovery(slightlyLow, Vec3.ZERO, List.of(floor));
        check(up != null && up.y > .01 && up.x == 0 && up.z == 0, "small legacy floor penetration recovers upward");
        check(!slightlyLow.move(up).intersects(floor), "recovery never pushes the model through its floor");

        AABB ceiling = new AABB(-3, 2, -3, 3, 3, 3);
        AABB tall = new AABB(-.5, 1.3, -.5, .5, 2.3, .5);
        Vec3 down = GroundLootWorldPhysics.recovery(tall, new Vec3(0, 1.3, 0), List.of(ceiling));
        check(down != null && down.y < -.3, "a one-block item thrown below a low ceiling may correct downward");
        check(!tall.move(down).intersects(ceiling), "ceiling recovery remains in the same room");

        AABB left = new AABB(-3, -1, -3, -.4, 3, 3), right = new AABB(.4, -1, -3, 3, 3, 3);
        check(GroundLootWorldPhysics.recovery(body, Vec3.ZERO, List.of(left, right, floor)) == null,
                "a model too long for the corridor stays retained and blocked");
        AABB thinWall = new AABB(.8, -1, -3, .85, 3, 3);
        AABB eitherSide = new AABB(-.25, 0, -.2, 1.25, .1, .2);
        Vec3 sameRoom = GroundLootWorldPhysics.recovery(eitherSide, new Vec3(.5, 0, 0), List.of(thinWall));
        check(sameRoom != null && sameRoom.x < 0, "a free endpoint on the far side is rejected because the carrier cannot cross the thin wall");

        AABB cornerBody = new AABB(-.7, -.01, -.2, .7, .2, .2);
        AABB cornerWall = new AABB(.6, -1, -3, 3, 3, 3);
        Vec3 corner = GroundLootWorldPhysics.recovery(cornerBody, Vec3.ZERO, List.of(floor, cornerWall));
        check(corner != null && corner.x < 0 && corner.y > 0, "a floor-wall corner uses a short two-axis correction");
        check(!cornerBody.move(corner).intersects(floor) && !cornerBody.move(corner).intersects(cornerWall), "both obstacles remain outside the recovered model");
        check(GroundLootWorldPhysics.recovery(body, Vec3.ZERO, List.of(new AABB(-2, -2, -2, 2, 2, 2))) == null,
                "a carrier already enclosed by a block is retained rather than teleported out");
        System.out.println("Ground loot recovery checks passed: " + checks);
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
