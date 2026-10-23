package com.example.examplemod.gunbalance.heavy;

import net.minecraft.resources.ResourceLocation;

/** Independent terrain and center-knockback targets for one approved heavy-weapon resource snapshot. */
public final class HeavyWeaponExplosionPolicy {
    private HeavyWeaponExplosionPolicy() {}

    public static HeavyWeaponProfiles.Profile freeze(ResourceLocation firedGun, String loadedProfileId) {
        if (firedGun == null || loadedProfileId == null || !firedGun.toString().equals(loadedProfileId)) return null;
        return HeavyWeaponProfiles.forGun(firedGun);
    }

    public static float terrainPower(float nativeRadius, HeavyWeaponProfiles.Profile shot) {
        return shot == null ? nativeRadius : (float) shot.terrainPower();
    }

    public static float damageRadius(float nativeRadius, HeavyWeaponProfiles.Profile shot) {
        return shot == null ? nativeRadius : (float) shot.damageRadius();
    }

    /** TaCZ already resolved obstruction and its closest visible hitbox sample into 1-r/R. */
    public static double damageMultiplier(double nativeMultiplier, HeavyWeaponProfiles.Profile shot) {
        if (shot == null) return nativeMultiplier;
        double normalizedDistance = Math.clamp(1 - nativeMultiplier, 0, 1);
        if (normalizedDistance >= 1) return 0;
        return Math.pow(Math.max(0, 1 - Math.pow(normalizedDistance, shot.curveP())), shot.curveQ());
    }

    public static float damageAmount(float nativeAmount, float centerDamage, HeavyWeaponProfiles.Profile shot) {
        if (shot == null || centerDamage <= 0 || !Float.isFinite(centerDamage)) return nativeAmount;
        return (float) (centerDamage * damageMultiplier(nativeAmount / centerDamage, shot));
    }

    /** Native distance falloff, resistance and both knockback switches run after this center coefficient. */
    public static float clampCenterImpulse(float nativeImpulse, HeavyWeaponProfiles.Profile shot) {
        return shot == null ? nativeImpulse : Math.min(nativeImpulse, (float) shot.maximumCenterKnockbackImpulse());
    }
}
