package com.example.examplemod.inventory;

/** No game bootstrap: geometric reach and item/ammunition conservation boundaries. */
public final class PubgInventoryChecks {
    private static int assertions;
    public static void main(String[] args) {
        check(PubgInventoryTargets.firstCandidate(1, slot -> true) == 1, "held second gun precedes first gun");
        check(PubgInventoryTargets.firstCandidate(2, slot -> true) == 2, "held handgun has equal priority");
        check(PubgInventoryTargets.firstCandidate(1, slot -> slot != 1) == 0, "incompatible held gun falls back");
        check(PubgInventoryTargets.firstCandidate(0, slot -> slot == 1) == 1, "identical installed part can be excluded");
        check(PubgInventoryTargets.firstCandidate(8, slot -> slot < 2) == 0, "holding a non-gun uses stable gun order");
        check(PubgInventoryTargets.firstCandidate(1, slot -> false) == -1, "no eligible gun has no default target");
        check(PubgInventoryRules.withinNearby(0, 0, 0), "feet origin");
        check(PubgInventoryRules.withinNearby(1, 0, 0), "inclusive radius boundary");
        check(!PubgInventoryRules.withinNearby(Math.nextUp(1.0), 0, 0), "just outside radius");
        check(PubgInventoryRules.withinNearby(.6, 0, .8), "Euclidean triangle boundary");
        check(!PubgInventoryRules.withinNearby(.8, 0, .8), "AABB diagonal is not nearby");
        check(!PubgInventoryRules.withinNearby(0, 1.01, 0), "floor height counts");
        check(!PubgInventoryRules.withinNearby(Double.NaN, 0, 0), "invalid distance");
        check(!PubgInventoryRules.withinNearby(0, Double.POSITIVE_INFINITY, 0), "infinite distance");
        check(PubgInventoryRules.capacityChange(70_000, 70_000, 70_000, 70_000), "exact capacity");
        check(!PubgInventoryRules.capacityChange(70_000, 70_001, 70_000, 70_000), "one unit overflow");
        check(PubgInventoryRules.capacityChange(90_000, 85_000, 70_000, 70_000), "existing overload can decrease");
        check(PubgInventoryRules.capacityChange(90_000, 90_000, 70_000, 70_000), "existing overload can rearrange");
        check(!PubgInventoryRules.capacityChange(90_000, 90_001, 70_000, 70_000), "existing overload cannot grow");
        check(!PubgInventoryRules.capacityChange(300_000, 250_000, 320_000, 220_000), "smaller pack must fit even after reduction");
        check(PubgInventoryRules.capacityChange(300_000, 220_000, 320_000, 220_000), "smaller pack exact fit");
        check(!PubgInventoryRules.capacityChange(100, -1, 100, 100), "negative final weight");
        check(PubgInventoryRules.preservesAmmunition(30, true, 30, true, 30), "chamber remains separate");
        check(!PubgInventoryRules.preservesAmmunition(40, true, 40, true, 30), "loaded larger magazine cannot shrink");
        check(!PubgInventoryRules.preservesAmmunition(40, true, 30, true, 30), "no silent ammunition loss");
        check(!PubgInventoryRules.preservesAmmunition(30, true, 30, false, 40), "no chamber round loss");
        check(!PubgInventoryRules.preservesAmmunition(30, false, 30, true, 40), "no chamber round creation");
        check(PubgInventoryRules.preservesAmmunition(0, false, 0, false, 0), "unloaded magazine");
        for (int used = 0; used <= 500; used++) {
            check(PubgInventoryRules.capacityChange(used, used, 320, 320), "rearranging never adds weight");
            if (used > 220) check(!PubgInventoryRules.capacityChange(used, used, 320, 220), "downgrade cannot retain excess");
        }
        System.out.println("PubgInventoryChecks: " + assertions + " assertions passed");
    }
    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
}
