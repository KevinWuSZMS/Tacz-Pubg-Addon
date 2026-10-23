package com.example.examplemod.backpack;

/** Integer thousandths avoid boundary drift for e.g. 9 mm ammunition (0.375). */
public final class BackpackRules {
    public static final int UNIT = 1000;
    public static final int BASE = 70 * UNIT;
    public static final int VEST = 50 * UNIT;
    public static final int SATURATED = 1_000_000_000;
    private BackpackRules() {}

    public static int bonus(int tier) {
        return switch (tier) { case 1 -> 150 * UNIT; case 2 -> 200 * UNIT; case 3 -> 250 * UNIT; default -> 0; };
    }
    public static int capacity(int tier, boolean vest) {
        return BASE + bonus(tier) + (vest ? VEST : 0);
    }
    public static int accepted(int used, int capacity, int unitWeight, int requested) {
        if (requested <= 0) return 0;
        if (unitWeight <= 0) return requested;
        return (int) Math.min(requested, Math.max(0L, (long) capacity - used) / unitWeight);
    }
    /** An existing overload can be reduced or rearranged, but cannot grow. */
    public static boolean permitsChange(int before, int after, int capacity) {
        return after <= capacity || after <= before;
    }
    public static boolean permitsEquipmentChange(int used, int incomingWeight, int oldPackWeight, int nextCapacity) {
        return Math.max(0L, (long) used - incomingWeight) + oldPackWeight <= nextCapacity;
    }
    public static int add(int a, long b) {
        return (int) Math.clamp((long) a + b, 0, SATURATED);
    }
}
