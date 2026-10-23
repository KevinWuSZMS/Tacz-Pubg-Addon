package com.example.examplemod.lootspawn;

import net.minecraft.world.phys.AABB;

/** Pure support, quantity, delay and migration checks; no game is started. */
public final class DroppedLootChecks {
    private static int checks;

    private DroppedLootChecks() {}

    public static void main(String[] args) {
        supportChecks();
        quantityChecks();
        delayChecks();
        yawChecks();
        originChecks();
        System.out.println("Dropped loot policy checks passed: " + checks);
    }

    private static void supportChecks() {
        AABB fullBlock = new AABB(0, 0, 0, 1, 1, 1);
        check(DroppedLootPolicy.supports(item(.5, 1, .5), fullBlock),
                "a full-block top supports a drop at its actual feet height");
        check(!DroppedLootPolicy.supports(item(.5, 1.01, .5), fullBlock),
                "a nearby floor does not count before real contact");
        check(!DroppedLootPolicy.supports(item(.5, .99, .5), fullBlock),
                "an embedded drop does not count as top-face contact");
        check(DroppedLootPolicy.supports(item(.5, 1.00001, .5), fullBlock),
                "ordinary floating-point error at the supporting face is tolerated");
        check(!DroppedLootPolicy.supports(item(.5, 1.001, .5), fullBlock),
                "contact tolerance does not bridge a visible air gap");

        AABB halfSlab = new AABB(0, 0, 0, 1, .5, 1);
        check(DroppedLootPolicy.supports(item(.5, .5, .5), halfSlab),
                "a bottom slab supports at half-block height");
        check(!DroppedLootPolicy.supports(item(.5, 1, .5), halfSlab),
                "a slab is not rounded up to a full block");
        AABB topSlab = new AABB(0, .5, 0, 1, 1, 1);
        check(DroppedLootPolicy.supports(item(.5, 1, .5), topSlab),
                "a top slab keeps its real upper surface");

        AABB stairBase = new AABB(0, 0, 0, 1, .5, 1);
        AABB stairStep = new AABB(.5, .5, 0, 1, 1, 1);
        check(DroppedLootPolicy.supports(item(.2, .5, .5), stairBase),
                "the lower stair tread supports at its own height");
        check(!DroppedLootPolicy.supports(item(.2, .5, .5), stairStep),
                "the upper stair tread does not pull a lower drop upwards");
        check(DroppedLootPolicy.supports(item(.8, 1, .5), stairStep),
                "the upper stair tread supports at its own height");
        check(!DroppedLootPolicy.supports(item(.8, 1, .5), stairBase),
                "the lower stair box is not mistaken for the upper contact");

        AABB fencePost = new AABB(.375, 0, .375, .625, 1.5, .625);
        check(DroppedLootPolicy.supports(item(.5, 1.5, .5), fencePost),
                "a narrow fence collision extending above one block supports correctly");
        check(!DroppedLootPolicy.supports(item(.5, 1, .5), fencePost),
                "fence support uses collision height rather than block height");

        AABB negativeSlab = new AABB(-4, -3, -7, -3, -2.5, -6);
        check(DroppedLootPolicy.supports(item(-3.5, -2.5, -6.5), negativeSlab),
                "negative world coordinates and a negative half-height surface remain exact");
        check(!DroppedLootPolicy.supports(item(-3.5, -2, -6.5), negativeSlab),
                "negative coordinates do not round a slab to the next block");

        AABB wall = new AABB(1, 0, 0, 2, 3, 1);
        check(!DroppedLootPolicy.supports(item(.875, 1, .5), wall),
                "horizontal contact against a wall is not ground support");
        check(!DroppedLootPolicy.supports(item(1.125, 1, .5), fullBlock),
                "a shared X edge with zero area overlap is not support");
        check(!DroppedLootPolicy.supports(item(.5, 1, 1.125), fullBlock),
                "a shared Z edge with zero area overlap is not support");
        check(!DroppedLootPolicy.supports(item(1.125, 1, 1.125), fullBlock),
                "a shared corner with zero area overlap is not support");
        check(DroppedLootPolicy.supports(item(1.12, 1, .5), fullBlock),
                "real partial overlap supports a drop at the floor edge");
        check(!DroppedLootPolicy.supports(item(1.14, 1, .5), fullBlock),
                "a separated item is not pulled back onto a floor edge");
        check(!DroppedLootPolicy.supports(item(.5, 5, .5), fullBlock),
                "a floating item high above a solid block is not supported");
        check(!DroppedLootPolicy.supports(item(.5, .9, .5), fullBlock),
                "fluid-height coincidence is not a solid top-face contact");
    }

    private static AABB item(double x, double feetY, double z) {
        return new AABB(x - .125, feetY, z - .125, x + .125, feetY + .25, z + .125);
    }

    private static void quantityChecks() {
        for (int quantity : new int[] {1, 2, 30, 64, 65, 256, 4095, LootPileQuantities.MAX_QUANTITY}) {
            check(DroppedLootPolicy.legalQuantity(quantity),
                    "positive legal logical quantities are retained without truncation");
        }
        for (int quantity : new int[] {Integer.MIN_VALUE, -1, 0, LootPileQuantities.MAX_QUANTITY + 1,
                Integer.MAX_VALUE}) {
            check(!DroppedLootPolicy.legalQuantity(quantity),
                    "empty, negative and over-budget quantities remain native rather than losing items");
        }
    }

    private static void delayChecks() {
        for (int delay : new int[] {0, 1, 10, 40, DroppedLootPolicy.NEVER_PICKUP - 1}) {
            check(DroppedLootPolicy.legalDelay(delay), "finite native pickup delays can be preserved");
        }
        for (int delay : new int[] {Integer.MIN_VALUE, -1, DroppedLootPolicy.NEVER_PICKUP,
                DroppedLootPolicy.NEVER_PICKUP + 1, Integer.MAX_VALUE}) {
            check(!DroppedLootPolicy.legalDelay(delay),
                    "never-pickup and malformed delays do not become immediately collectible loot");
        }
    }

    private static void yawChecks() {
        float[][] cases = {
                {0, 0}, {1, 1}, {90, 90}, {180, 180}, {359, 359}, {360, 0}, {361, 1},
                {720, 0}, {1080.25F, .25F}, {-1, 359}, {-90, 270}, {-359, 1}, {-360, 0},
                {-361, 359}, {-720, 0}, {-1080.25F, 359.75F}
        };
        for (float[] pair : cases) {
            float yaw = DroppedLootPolicy.stableYaw(pair[0]);
            check(Math.abs(yaw - pair[1]) < 1e-4,
                    "yaw keeps the same physical angle when wrapping through zero and 360");
            check(yaw >= 0 && yaw < 360, "normalized yaw remains in the finite render range");
            check(DroppedLootPolicy.stableYaw(yaw) == yaw,
                    "repeated rendering and ground conversion do not rotate a fixed yaw");
        }
        for (float malformed : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            check(DroppedLootPolicy.stableYaw(malformed) == 0,
                    "nonfinite yaw receives a deterministic safe orientation");
        }
        float fixed = DroppedLootPolicy.stableYaw(-721.25F);
        for (int tick = 0; tick < 200; tick++) {
            check(DroppedLootPolicy.stableYaw(-721.25F) == fixed,
                    "fixed source yaw has no tick- or age-driven spin");
        }
    }

    private static void originChecks() {
        check(DroppedLootPolicy.RESPAWN.equals(DroppedLootPolicy.restoredOrigin(false, "")),
                "legacy saves without Origin retain respawn ownership");
        check(DroppedLootPolicy.RESPAWN.equals(DroppedLootPolicy.restoredOrigin(false, null)),
                "absent Origin does not depend on an unused fallback value");
        check(DroppedLootPolicy.RESPAWN.equals(DroppedLootPolicy.restoredOrigin(true, DroppedLootPolicy.RESPAWN)),
                "new respawn-owned loot retains refresh ownership after restoration");
        check(DroppedLootPolicy.DROP.equals(DroppedLootPolicy.restoredOrigin(true, DroppedLootPolicy.DROP)),
                "player drops retain their independent identity after restoration");
        for (String origin : new String[] {"", "unknown", "DROP", "Respawn", "drop ", null}) {
            rejects(() -> DroppedLootPolicy.restoredOrigin(true, origin),
                    "unknown origin cannot silently become respawn-owned and be deleted by a refresh");
        }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void rejects(Runnable action, String description) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }
}
