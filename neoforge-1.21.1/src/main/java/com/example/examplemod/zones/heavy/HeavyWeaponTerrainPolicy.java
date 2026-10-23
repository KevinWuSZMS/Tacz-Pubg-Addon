package com.example.examplemod.zones.heavy;

/** An authored per-gun switch is applied once to that shot, with TaCZ's server terrain switch retained. */
public final class HeavyWeaponTerrainPolicy {
    private HeavyWeaponTerrainPolicy() {}
    public static boolean resolve(boolean nativeValue, Boolean authoredOverride, boolean serverAllowsDestruction) {
        return authoredOverride == null ? nativeValue : authoredOverride && serverAllowsDestruction;
    }
}
