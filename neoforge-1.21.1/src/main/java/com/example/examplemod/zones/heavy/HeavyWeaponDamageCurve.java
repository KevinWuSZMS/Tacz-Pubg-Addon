package com.example.examplemod.zones.heavy;

import com.example.examplemod.localization.Texts;

/** Per-gun geometric falloff. Armour, obstruction and native damage events still run afterwards. */
public record HeavyWeaponDamageCurve(double damageRadius, double p, double q) {
    public HeavyWeaponDamageCurve {
        if (!Double.isFinite(damageRadius) || damageRadius <= 0 || damageRadius > 128
                || !Double.isFinite(p) || p < 1 || p > 2 || !Double.isFinite(q) || q < 1 || q > 2)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.heavy_weapon_damage_curve.heavy_weapon_radius_blocks_p_q"));
    }

    public double multiplierAt(double distance) {
        if (!Double.isFinite(distance) || distance < 0 || distance >= damageRadius) return 0;
        return Math.pow(Math.max(0, 1 - Math.pow(distance / damageRadius, p)), q);
    }
}
