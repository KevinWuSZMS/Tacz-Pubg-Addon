package com.example.examplemod.inventory;

/** Transparent mod policy using a fixed 100 PUBG-HP sustained-damage reference for vests. */
public final class EquipmentPreference {
    private EquipmentPreference() {}
    public static double vestScore(int tier, double durability) {
        double multiplier = switch (tier) { case 1 -> .7; case 2 -> .6; case 3 -> .45; default -> 1; };
        if (durability <= 0) return 125;
        if (multiplier >= 1) return 100;
        double absorbedHits = durability / (1 - multiplier);
        return absorbedHits * multiplier >= 100 ? 100 / multiplier
                : absorbedHits + (100 - absorbedHits * multiplier) / .8;
    }
    public static boolean betterArmor(boolean helmet, int incomingTier, double incomingHealth, int oldTier, double oldHealth) {
        if (incomingHealth <= 0) return false;
        if (oldHealth <= 0) return true;
        if (incomingTier == oldTier) return incomingHealth >= oldHealth + 1;
        if (helmet) return incomingTier > oldTier;
        double incoming = vestScore(incomingTier, incomingHealth), old = vestScore(oldTier, oldHealth);
        return incoming >= old + 5 || incomingTier > oldTier && incoming >= old;
    }
}
