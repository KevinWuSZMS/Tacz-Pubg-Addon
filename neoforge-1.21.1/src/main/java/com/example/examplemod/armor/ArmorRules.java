package com.example.examplemod.armor;

/** PUBG-scale health is 5x Minecraft health. Armor health keeps 0.01-point precision. */
public final class ArmorRules {
    public static final int PRECISION = 100;
    private ArmorRules() {}
    public static int maximum(boolean helmet, int tier) {
        if (tier < 1 || tier > 3) throw new IllegalArgumentException("Armor tier must be 1..3");
        return (helmet ? new int[]{80, 150, 230} : new int[]{200, 220, 250})[tier - 1];
    }
    public static float multiplier(int tier, boolean helmet, boolean broken) {
        if (tier < 1 || tier > 3) return 1;
        // Official PUBG Update 7.2: destroyed vests retain 20% protection at every tier.
        if (broken) return helmet ? 1 : .8f;
        return switch (tier) { case 1 -> .7f; case 2 -> .6f; default -> .45f; };
    }
    public static int wear(float incomingMinecraftDamage, float multiplier) {
        if (!Float.isFinite(incomingMinecraftDamage) || incomingMinecraftDamage <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(incomingMinecraftDamage * 5.0 * (1 - multiplier) * PRECISION));
    }
    public static int remaining(int maximum, int damage) { return Math.max(0, maximum - Math.max(0, damage)); }
    public record WearResult(int damage, boolean removeItem) {}
    /** Depletion destroys helmets; the retained zero-health vest still owns its storage bonus. */
    public static WearResult afterWear(boolean helmet, int maximum, int previousDamage, int wear) {
        // Commands may remove the max-damage component; keep the old safe depletion behavior.
        if (maximum <= 0) return new WearResult(0, helmet);
        int damage = (int) Math.clamp((long) previousDamage + Math.max(0, wear), 0, maximum);
        return new WearResult(damage, helmet && damage == maximum);
    }
}
