package com.example.examplemod.inventory;

/** Pure transaction boundaries shared by the inventory authority and offline checks. */
public final class PubgInventoryRules {
    static final double NEARBY_RADIUS = 2.0;
    private PubgInventoryRules() {}
    public static boolean withinNearby(double dx, double dy, double dz) {
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && dx * dx + dy * dy + dz * dz <= NEARBY_RADIUS * NEARBY_RADIUS;
    }
    public static boolean capacityChange(long beforeUsed, long afterUsed, int beforeCapacity, int afterCapacity) {
        if (beforeUsed < 0 || afterUsed < 0 || beforeCapacity < 0 || afterCapacity < 0) return false;
        return afterUsed <= afterCapacity || afterUsed <= beforeUsed && afterCapacity >= beforeCapacity;
    }
    public static boolean preservesAmmunition(int oldRounds, boolean oldChamber, int newRounds, boolean newChamber, int capacity) {
        return oldRounds >= 0 && capacity >= 0 && oldRounds == newRounds && oldChamber == newChamber && newRounds <= capacity;
    }
}
