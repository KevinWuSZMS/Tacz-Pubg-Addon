package com.example.examplemod.lootspawn;

import java.util.List;

import com.example.examplemod.lootspawn.client.GroundLootFlatPose;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure quantity/geometry checks; no Minecraft, TaCZ, rendering, client or server is started. */
public final class GroundLootChecks {
    private static int checks;

    public static void main(String[] args) {
        check(LootPileQuantities.restored(1, false, 0, 32, 64) == 32,
                "legacy Item count migrates to the logical amount");
        check(LootPileQuantities.restored(1, false, 0, 64, 64) == 64,
                "legacy full legal stack migrates without loss");
        check(LootPileQuantities.restored(1, true, 300, 16, 64) == 16,
                "version 1 keeps its Item count authoritative");
        check(LootPileQuantities.restored(2, true, 140, 1, 64) == 140,
                "one new logical ammo pile can exceed the physical stack limit");
        check(LootPileQuantities.restored(2, true, 4096, 1, 1) == 4096,
                "logical quantity cap is independent of stackability");
        check(LootPileQuantities.restored(2, true, 1, 1, 64) == 1, "minimum nonempty logical amount is accepted");
        rejects(() -> LootPileQuantities.restored(1, false, 0, 65, 64), "overfull legacy Item is rejected");
        rejects(() -> LootPileQuantities.restored(1, false, 0, 0, 64), "empty legacy Item is rejected");
        rejects(() -> LootPileQuantities.restored(1, false, 0, -1, 64), "negative legacy Item count is rejected");
        rejects(() -> LootPileQuantities.restored(2, false, 64, 1, 64), "version 2 requires explicit Quantity");
        rejects(() -> LootPileQuantities.restored(2, true, 64, 2, 64), "version 2 rejects a noncanonical base count");
        rejects(() -> LootPileQuantities.restored(2, true, 64, 0, 64), "version 2 rejects an empty base item");
        rejects(() -> LootPileQuantities.restored(2, true, 0, 1, 64), "empty saved logical quantity is rejected");
        rejects(() -> LootPileQuantities.restored(2, true, -1, 1, 64), "negative saved logical quantity is rejected");
        rejects(() -> LootPileQuantities.restored(2, true, 4097, 1, 64), "saved logical quantity above cap is rejected");
        rejects(() -> LootPileQuantities.restored(3, true, 1, 1, 64), "unknown future save version is rejected");
        rejects(() -> LootPileQuantities.restored(0, false, 0, 1, 64), "missing/unsupported old save version is rejected");
        rejects(() -> LootPileQuantities.restored(2, true, 1, 1, 0), "invalid stack capacity is rejected on restoration");

        check(LootPileQuantities.batches(140, 64).equals(List.of(64, 64, 12)),
                "magazine-sized logical pile splits only for legal inventory insertion");
        check(LootPileQuantities.batches(75, 64).equals(List.of(64, 11)), "partial final stack keeps every round");
        check(LootPileQuantities.batches(64, 64).equals(List.of(64)), "exact stack limit is one insertion batch");
        check(LootPileQuantities.batches(0, 64).isEmpty(), "empty runtime pile has no insertion work");
        for (int limit : List.of(1, 2, 7, 16, 32, 64, 65, 99, 127, 256, 4096, Integer.MAX_VALUE)) {
            for (int quantity : List.of(0, 1, 15, 63, 64, 65, 140, 4095, 4096)) {
                List<Integer> batches = LootPileQuantities.batches(quantity, limit);
                check(batches.stream().allMatch(batch -> batch >= 1 && batch <= limit),
                        "arbitrary legal stack limits bound every insertion");
                check(batches.stream().mapToInt(Integer::intValue).sum() == quantity,
                        "all insertion batches preserve total quantity");
            }
        }
        rejects(() -> LootPileQuantities.batches(-1, 64), "negative runtime amount is rejected");
        rejects(() -> LootPileQuantities.batches(4097, 64), "over-cap runtime amount is rejected");
        rejects(() -> LootPileQuantities.batches(1, 0), "zero stack limit cannot create a nonterminating batch loop");
        rejects(() -> LootPileQuantities.batches(0, -1), "invalid limit is rejected even for an empty pile");

        Pickup full = simulatePickup(140, 64, 140, false);
        check(full.accepted == 140 && full.remaining == 0 && full.calls == 3,
                "full pickup inserts legal batches and empties the logical pile");
        Pickup partial = simulatePickup(140, 64, 70, false);
        check(partial.accepted == 70 && partial.remaining == 70 && partial.calls == 2,
                "partial second batch leaves exact logical remainder and stops");
        Pickup unavailable = simulatePickup(140, 64, 0, false);
        check(unavailable.accepted == 0 && unavailable.remaining == 140 && unavailable.calls == 1,
                "no real inventory gain leaves the complete pile unchanged");
        Pickup creative = simulatePickup(140, 64, 70, true);
        check(creative.accepted == 70 && creative.remaining == 70 && creative.calls == 2,
                "creative overflow deletion is not counted as actual pickup");
        Pickup unstackable = simulatePickup(4, 1, 2, false);
        check(unstackable.accepted == 2 && unstackable.remaining == 2,
                "unstackable items also retain their partial logical remainder");
        flatPoseChecks();
        targetingGeometryChecks();
        visualScaleChecks();
        nearestPickupChecks();
        System.out.println("Ground loot quantity and pose checks passed: " + checks);
    }

    private static void nearestPickupChecks() {
        Vec3 eye = new Vec3(0, .08, -3), end = new Vec3(0, .08, 3);
        AABB model = new AABB(-.6, 0, -.2, .6, .15, .2);
        var near = new GroundLootRaycast.Target(7, model, new Vec3(0, 0, -1), 32);
        var behind = new GroundLootRaycast.Target(2, model, new Vec3(0, 0, 1), -19);
        check(GroundLootRaycast.nearest(eye, end, 36, List.of(behind, near)).orElseThrow().id() == 7,
                "authoritative pickup selects the closest oriented model even when a farther ID appears first");
        check(GroundLootRaycast.nearest(eye, end, 1, List.of(near, behind)).isEmpty(),
                "a nearer wall clips the complete candidate set and prevents through-wall pickup");
        var offRay = new GroundLootRaycast.Target(1, model, new Vec3(2, 0, -2), 0);
        check(GroundLootRaycast.nearest(eye, end, 36, List.of(offRay, behind)).orElseThrow().id() == 2,
                "closer entities outside the actual model ray cannot hide the intended item");
        var coincident = new GroundLootRaycast.Target(3, model, new Vec3(0, 0, -1), 32);
        check(GroundLootRaycast.nearest(eye, end, 36, List.of(near, coincident)).orElseThrow().id() == 3
                        && GroundLootRaycast.nearest(eye, end, 36, List.of(coincident, near)).orElseThrow().id() == 3,
                "coincident bounds use a stable entity-ID tie break instead of list order");
        check(GroundLootRaycast.nearest(eye, new Vec3(0, .08, -2.5), 36, List.of(near)).isEmpty(),
                "an out-of-reach model is not selected even when the wall distance permits it");
    }

    private static void visualScaleChecks() {
        for (double[] dimensions : List.of(new double[] {2, .12, .5}, new double[] {.2, .3, .4}, new double[] {.08, .08, .08})) {
            check(GroundLootVisualPolicy.extraScale(dimensions[0], dimensions[1], dimensions[2]) == 1,
                    "volumetric guns, ammo boxes and small cubes retain ordinary dropped-item scale");
        }
        for (double[] dimensions : List.of(new double[] {.8, .02, .5}, new double[] {0, .5, .8}, new double[] {.5, .8, .02}, new double[] {.04, 1, .2})) {
            check(GroundLootVisualPolicy.extraScale(dimensions[0], dimensions[1], dimensions[2]) == 1,
                    "thin ammo, extensions and flat models retain native drop size regardless of authored axis");
        }
        check(GroundLootVisualPolicy.extraScale(.03, .03, .03) == 1,
                "small native volumes retain their authored size");
        check(GroundLootVisualPolicy.extraScale(.041, 5, .3) == 1,
                "a long rifle does not shrink merely because its aspect ratio is high");
        check(GroundLootVisualPolicy.extraScale(.03, .5, .4) == 1,
                "changing thinness cannot add a custom size multiplier");
        check(GroundLootVisualPolicy.ICON_SIZE == 1, "native TaCZ SlotModel is one block before baked GROUND transforms");
        check(GroundLootVisualPolicy.PROFILE_VERSION == 4, "old shrunken footprint profiles cannot survive the native-size update");
        rejects(() -> GroundLootVisualPolicy.extraScale(0, 0, 0), "empty geometry is rejected");
        rejects(() -> GroundLootVisualPolicy.extraScale(-1, 1, 1), "inverted geometry is rejected");
        rejects(() -> GroundLootVisualPolicy.extraScale(Double.NaN, 1, 1), "nonfinite geometry is rejected");
        AABB ammo = GroundLootFootprints.ammoBounds(ResourceLocation.parse("cbrg:ammo_762"));
        check(ammo.minY == 0 && ammo.getYsize() > .02 && ammo.getXsize() > .1,
                "matching ammo has a saved three-dimensional or thin-model footprint, not a fixed former icon box");
        AABB iconAmmo = GroundLootFootprints.ammoBounds(ResourceLocation.parse("ccrp:545x39_beans"));
        check(iconAmmo.getXsize() == 1 && iconAmmo.getZsize() == 1,
                "icon-only ammo matches native one-block SlotModel after identity baked GROUND transform");
        AABB iconAttachment = GroundLootFootprints.attachmentBounds(ResourceLocation.parse("ccrp:ammo_mod_hap"));
        check(iconAttachment.getXsize() == .5 && iconAttachment.getZsize() == .5,
                "icon-only attachment preserves authored native GROUND 0.5 instead of adding or removing scaling");
    }

    private static void targetingGeometryChecks() {
        Vec3 origin = new Vec3(1_000_000.25, 80.125, -1_000_000.75);
        AABB local = new AABB(-1, 0, -.2, 1, .15, .2);
        for (float yaw : List.of(0F, 30F, 45F, 90F, 180F, 270F, -90F, -1F, 359F)) {
            for (Vec3 p : List.of(new Vec3(0, 0, 0), new Vec3(1, .15, .2), new Vec3(-1, .1, -.2))) {
                Vec3 world = GroundLootFootprints.worldPoint(p, origin, yaw);
                Vec3 restored = GroundLootFootprints.localPoint(world, origin, yaw);
                check(restored.distanceToSqr(p) < 1e-16, "OBB inverse transforms preserve points at large world coordinates");
                AABB enclosing = GroundLootFootprints.worldBounds(local, origin, yaw).inflate(1e-8);
                check(enclosing.contains(world), "world culling box contains each rotated model corner");
            }
            Vec3 rayStart = GroundLootFootprints.worldPoint(new Vec3(0, 2, 0), origin, yaw);
            Vec3 rayEnd = GroundLootFootprints.worldPoint(new Vec3(0, -1, 0), origin, yaw);
            check(local.clip(GroundLootFootprints.localPoint(rayStart, origin, yaw),
                    GroundLootFootprints.localPoint(rayEnd, origin, yaw)).isPresent(), "vertical aim hits the rotated floor model");
            Vec3 outside = GroundLootFootprints.worldPoint(new Vec3(0, 2, .30), origin, yaw);
            Vec3 outsideEnd = GroundLootFootprints.worldPoint(new Vec3(0, -1, .30), origin, yaw);
            check(local.clip(GroundLootFootprints.localPoint(outside, origin, yaw),
                    GroundLootFootprints.localPoint(outsideEnd, origin, yaw)).isEmpty(), "OBB rejects empty space outside its narrow side");
        }
        AABB beryl = GroundLootFootprints.gunBounds(ResourceLocation.parse("cbrg:beryl_m762"));
        check(beryl.minY == 0 && beryl.getZsize() > 1.5 && beryl.getYsize() > GroundLootFootprints.FLOOR_LIFT,
                "bundled full-scale Beryl bounds include the floor lift instead of old 0.38 shrink");
        AABB unknown = GroundLootFootprints.gunBounds(ResourceLocation.parse("test:unknown_gun"));
        check(unknown.minY == 0 && unknown.getXsize() == 3 && unknown.getZsize() == 1.3,
                "new guns without a baked footprint reserve the documented conservative space");
        check(Math.abs(GroundLootFootprints.worldBounds(local, origin, 90).getXsize() - .4) < 1e-8
                && Math.abs(GroundLootFootprints.worldBounds(local, origin, 90).getZsize() - 2) < 1e-8,
                "quarter-turn swaps the rectangular rifle footprint axes");
        AABB diagonal = LootSpawnPlacement.rotate(local, 45);
        check(diagonal.getXsize() > local.getZsize() && diagonal.getZsize() > local.getZsize() && diagonal.minY == 0,
                "actual placement expands both horizontal axes for a diagonal rifle while retaining ground height");
        AABB negative = LootSpawnPlacement.rotate(local, -1), positive = LootSpawnPlacement.rotate(local, 359);
        check(Math.abs(negative.minX - positive.minX) < 1e-12 && Math.abs(negative.maxZ - positive.maxZ) < 1e-12,
                "negative angles and their positive equivalent reserve the same floor space");
        java.util.Random firstSeed = new java.util.Random(871), secondSeed = new java.util.Random(871);
        boolean arbitraryAngle = false;
        for (int i = 0; i < 1000; i++) {
            float first = LootSpawnPlacement.randomYaw(firstSeed), second = LootSpawnPlacement.randomYaw(secondSeed);
            check(first >= 0 && first < 360, "every actual spawn yaw is within the half-open 0 to 360 range");
            check(first == second, "the respawn seed deterministically reproduces every arbitrary item yaw");
            arbitraryAngle |= Math.abs(first % 90) > 1e-4;
        }
        check(arbitraryAngle, "spawn rotation is not quantized to four directions");
    }

    private static void flatPoseChecks() {
        GroundLootFlatPose flat = GroundLootFlatPose.fromBounds(-2, 3, -4, 2, 3.2, 1);
        check(flat.xRotationDegrees() == 0 && flat.zRotationDegrees() == 0,
                "an authored flat model is not rotated again");
        GroundLootFlatPose thinZ = GroundLootFlatPose.fromBounds(2, 5, -8, 7, 7, -7.75);
        check(thinZ.xRotationDegrees() == 90 && thinZ.zRotationDegrees() == 0,
                "a Z-thin model uses X +90 to put its thin axis vertically");
        GroundLootFlatPose thinX = GroundLootFlatPose.fromBounds(-9, 1, 4, -8.8, 6, 7);
        check(thinX.xRotationDegrees() == 0 && thinX.zRotationDegrees() == 90,
                "an X-thin model uses Z +90 to put its thin axis vertically");
        GroundLootFlatPose almostFlat = GroundLootFlatPose.fromBounds(0, 0, 0, 1, 1.03, 3);
        check(almostFlat.xRotationDegrees() == 0 && almostFlat.zRotationDegrees() == 0,
                "small authored flatness differences do not cause an unnecessary quarter turn");

        for (double[] bounds : List.of(
                new double[] {-2, 3, -4, 2, 3.2, 1},
                new double[] {10, -6, -4, 18, -5.8, 3},
                new double[] {-12, 4, 7, -8, 4.15, 12},
                new double[] {2, 5, -8, 7, 7, -7.75},
                new double[] {-6, -10, 3, -3, -7, 3.4},
                new double[] {-9, 1, 4, -8.8, 6, 7},
                new double[] {4, -9, -7, 4.3, -3, -2})) {
            assertGroundedAndCentered(bounds);
        }
        for (int coordinate = 0; coordinate < 6; coordinate++) {
            double[] invalid = {0, 0, 0, 1, 1, 1};
            invalid[coordinate] = Double.NaN;
            rejects(() -> pose(invalid), "NaN is rejected in every bounds coordinate");
        }
        rejects(() -> GroundLootFlatPose.fromBounds(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1),
                "infinite model bounds are rejected");
        rejects(() -> GroundLootFlatPose.fromBounds(0, 0, 0, 0, 1, 1), "zero-width bounds are rejected");
        rejects(() -> GroundLootFlatPose.fromBounds(0, 0, 0, 1, 0, 1), "zero-height bounds are rejected");
        rejects(() -> GroundLootFlatPose.fromBounds(0, 0, 0, 1, 1, 0), "zero-depth bounds are rejected");
        rejects(() -> GroundLootFlatPose.fromBounds(2, 0, 0, 1, 1, 1), "inverted bounds are rejected");
        rejects(() -> GroundLootFlatPose.fromBounds(0, 0, 0, 1e-8, 1, 1), "near-degenerate bounds are rejected");
    }

    private static void assertGroundedAndCentered(double[] bounds) {
        GroundLootFlatPose placement = pose(bounds);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        double xRadians = Math.toRadians(placement.xRotationDegrees());
        double zRadians = Math.toRadians(placement.zRotationDegrees());
        for (int corner = 0; corner < 8; corner++) {
            double x = bounds[(corner & 1) == 0 ? 0 : 3];
            double y = bounds[(corner & 2) == 0 ? 1 : 4];
            double z = bounds[(corner & 4) == 0 ? 2 : 5];
            // Renderer matrix order T * Rx * Rz: a vertex is rotated around Z, then X, then translated.
            double rotatedX = x * Math.cos(zRadians) - y * Math.sin(zRadians);
            double aroundZy = x * Math.sin(zRadians) + y * Math.cos(zRadians);
            double rotatedY = aroundZy * Math.cos(xRadians) - z * Math.sin(xRadians);
            double rotatedZ = aroundZy * Math.sin(xRadians) + z * Math.cos(xRadians);
            double finalX = rotatedX + placement.offsetX();
            double finalY = rotatedY + placement.offsetY();
            double finalZ = rotatedZ + placement.offsetZ();
            check(finalY >= -1e-9, "none of the eight transformed bounds corners sinks below the floor");
            minX = Math.min(minX, finalX); maxX = Math.max(maxX, finalX);
            minY = Math.min(minY, finalY); maxY = Math.max(maxY, finalY);
            minZ = Math.min(minZ, finalZ); maxZ = Math.max(maxZ, finalZ);
        }
        check(Math.abs(minY) < 1e-9, "translated bounds rest at minY approximately zero");
        check(Math.abs((minX + maxX) / 2) < 1e-9, "noncentral positive/negative bounds become centered in X");
        check(Math.abs((minZ + maxZ) / 2) < 1e-9, "noncentral positive/negative bounds become centered in Z");
        check(maxY > 0, "flattened model retains positive thickness");
    }

    private static GroundLootFlatPose pose(double[] bounds) {
        return GroundLootFlatPose.fromBounds(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    /** Models Inventory.add's mutation and actual matching-component count, without game objects. */
    private static Pickup simulatePickup(int quantity, int stackLimit, int physicalCapacity, boolean creativeVoid) {
        int stored = 0;
        int accepted = 0;
        int calls = 0;
        for (int attempted : LootPileQuantities.batches(quantity, stackLimit)) {
            calls++;
            long beforeMatchingComponents = stored;
            int actual = Math.min(attempted, Math.max(0, physicalCapacity - stored));
            stored += actual;
            int mutatedRequestRemainder = creativeVoid ? 0 : attempted - actual;
            int counted = (int) Math.min(attempted, Math.max(0, (long) stored - beforeMatchingComponents));
            check(counted == actual, "only matching-component inventory growth counts as accepted");
            if (creativeVoid && counted < attempted) {
                check(attempted - mutatedRequestRemainder > counted,
                        "mutated request size alone would overcount creative-voided overflow");
            }
            accepted += counted;
            if (counted < attempted) break;
        }
        return new Pickup(accepted, quantity - accepted, calls);
    }

    private record Pickup(int accepted, int remaining, int calls) {}

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }
}
