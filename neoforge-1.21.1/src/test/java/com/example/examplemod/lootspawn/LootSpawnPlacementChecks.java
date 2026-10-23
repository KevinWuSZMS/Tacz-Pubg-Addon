package com.example.examplemod.lootspawn;

import com.example.examplemod.lootconfig.LootSpawnRules.FloorSample;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Placement shortages are normal outcomes; no world or item registry is needed for these fixtures. */
public final class LootSpawnPlacementChecks {
    private static int checks;

    private LootSpawnPlacementChecks() {}

    public static void main(String[] args) {
        noCandidateIsNotFailure();
        partialSupplyIsRetained();
        occupiedSlotsStayClear();
        budgetLimitsReservation();
        emptyRegionsSkipBothKinds();
        System.out.println("Loot placement shortage checks passed: " + checks);
    }

    private static void noCandidateIsNotFailure() {
        List<AABB> occupied = new ArrayList<>();
        check(LootSpawnPlacement.reserveAvailable(List.of(), 3, occupied).isEmpty(),
                "no legal ammunition or primary slot means no generated pile");
        check(occupied.isEmpty(), "an empty result never reserves dead space");
    }

    private static void partialSupplyIsRetained() {
        LootSpawnPlacement.Slot first = slot(0), second = slot(2);
        List<AABB> occupied = new ArrayList<>();
        var available = LootSpawnPlacement.reserveAvailable(List.of(first, second), 3, occupied);
        check(available.equals(List.of(first, second)), "two legal piles survive a request for three");
        check(occupied.size() == 2, "only the returned legal piles consume space");
        var following = slot(4);
        check(LootSpawnPlacement.reserveAvailable(List.of(following), 1, occupied).equals(List.of(following)),
                "a shortage does not prevent later independent loot from reserving space");
    }

    private static void occupiedSlotsStayClear() {
        LootSpawnPlacement.Slot blocked = slot(0), first = slot(2), overlapping = slot(2.2), separate = slot(4);
        AABB existing = blocked.bounds().inflate(.02, 0, .02);
        List<AABB> occupied = new ArrayList<>(List.of(existing));
        var available = LootSpawnPlacement.reserveAvailable(List.of(blocked, first, overlapping, separate), 5, occupied);
        check(available.equals(List.of(first, separate)),
                "partial generation still rejects existing occupancy and overlapping candidates");
        check(occupied.size() == 3 && occupied.getFirst().equals(existing),
                "rejected candidates add no ghost reservations or remove existing occupancy");
        var margin = slot(4.41);
        check(LootSpawnPlacement.reserveAvailable(List.of(margin), 1, occupied).isEmpty(),
                "the original horizontal clearance margin is preserved");
    }

    private static void budgetLimitsReservation() {
        LootSpawnPlacement.Slot first = slot(0), second = slot(2);
        List<AABB> occupied = new ArrayList<>();
        check(LootSpawnPlacement.reserveAvailable(List.of(first, second), 0, occupied).isEmpty()
                        && LootSpawnPlacement.reserveAvailable(List.of(first, second), -1, occupied).isEmpty(),
                "zero and exhausted budgets produce no reservation");
        check(occupied.isEmpty(), "zero budget never consumes candidate space");
        check(LootSpawnPlacement.reserveAvailable(List.of(first, second), 1, occupied).equals(List.of(first))
                        && occupied.size() == 1,
                "a positive budget stops at the requested number of piles");
    }

    private static void emptyRegionsSkipBothKinds() {
        var plane = new LootSpawnPlacement.FloorIndex(List.of());
        var anchor = new FloorSample(BlockPos.ZERO, 0);
        var gun = slot(0);
        List<AABB> occupied = new ArrayList<>();
        Random random = new Random(17);
        // Null world/items intentionally fail if either no-work branch tries world access or asset construction.
        check(LootSpawnPlacement.reservePrimary(null, anchor, plane, null, 1, random, occupied).isEmpty(),
                "a region with no approved floors skips primary loot without world or asset access");
        check(LootSpawnPlacement.reserveAmmo(null, gun, plane, null, 1, 1, 1.5, random, occupied).isEmpty(),
                "an empty approved floor plane skips companion ammo without aborting its gun");
        var populated = new LootSpawnPlacement.FloorIndex(List.of(anchor));
        check(LootSpawnPlacement.reservePrimary(null, anchor, populated, null, 0, random, occupied).isEmpty()
                        && LootSpawnPlacement.reserveAmmo(null, gun, populated, null, 0, 1, 1.5, random, occupied).isEmpty(),
                "both real entry points short-circuit exhausted budgets before world or asset access");
        check(occupied.isEmpty(), "no-work entry points leave occupancy unchanged");
    }

    private static LootSpawnPlacement.Slot slot(double x) {
        return new LootSpawnPlacement.Slot(BlockPos.ZERO, x, 0, 0, 0,
                new AABB(x, 0, 0, x + .4, .1, .4));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
