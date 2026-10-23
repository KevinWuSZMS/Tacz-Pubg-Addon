package com.example.examplemod.zones.heavy;

import com.tacz.guns.api.TimelessAPI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProfiles;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponGunData;

/** All native launcher/heavy types, including grenade launchers; bombing uses a narrower rocket subset. */
public final class HeavyWeaponCatalog {
    private static final Set<String> TYPES = Set.of("rpg", "rocket", "rocket_launcher", "grenade", "grenade_launcher",
            "grenadelauncher", "gl", "heavy", "heavy_weapon", "launcher");
    private HeavyWeaponCatalog() {}

    public static boolean isHeavyType(String type) {
        return type != null && TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    /** Raw native amounts, before attachments, shot multipliers, armor or the shared TaCZ damage multiplier. */
    public static Map<String, HeavyWeaponInfo> capture() {
        Map<String, HeavyWeaponInfo> result = new TreeMap<>();
        for (var entry : TimelessAPI.getAllCommonGunIndex()) {
            var index = entry.getValue();
            if (!isHeavyType(index.getType())) continue;
            var bullet = index.getBulletData();
            var ammo = index.getGunData().getAmmoId();
            if (ammo == null) continue;
            var info = fromNative(ammo.toString(), bullet);
            var profile = HeavyWeaponProfiles.forGun(entry.getKey());
            if (profile != null && (Object) index.getGunData() instanceof HeavyWeaponGunData data
                    && profile.gunId().toString().equals(data.taczPubg$heavyProfileId())) {
                info = new HeavyWeaponInfo(info.ammoId(), info.directDamage(), info.explosionDamage(), info.damageRadius(),
                        info.nativeDestroyBlocks(), profile.terrainPower(), true);
            }
            if (isGrenade(entry.getKey())) info = new HeavyWeaponInfo(info.ammoId(), info.directDamage(), info.explosionDamage(),
                    info.damageRadius(), info.nativeDestroyBlocks(), 3, info.experimentalBalance());
            result.put(entry.getKey().toString(), info);
        }
        return Collections.unmodifiableMap(result);
    }

    public static boolean isHeavy(ResourceLocation gun) {
        return gun != null && TimelessAPI.getCommonGunIndex(gun).map(index -> isHeavyType(index.getType())).orElse(false);
    }

    public static boolean isGrenade(ResourceLocation gun) {
        if (gun == null) return false;
        var profile = HeavyWeaponProfiles.forGun(gun);
        if (profile != null) return profile.grenade();
        return TimelessAPI.getCommonGunIndex(gun).map(index -> isGrenadeType(index.getType(), gun.toString(),
                index.getGunData().getAmmoId() == null ? "" : index.getGunData().getAmmoId().toString())).orElse(false);
    }

    /** Same dynamic ammo/name precedence as supply defaults; r7 also labels grenade launchers as rpg. */
    public static boolean isGrenadeType(String type, String gunId, String ammoId) {
        String nativeType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String gun = gunId == null ? "" : gunId.toLowerCase(Locale.ROOT);
        String ammo = ammoId == null ? "" : ammoId.toLowerCase(Locale.ROOT);
        String gunPath = gun.substring(gun.indexOf(':') + 1), ammoPath = ammo.substring(ammo.indexOf(':') + 1);
        return Set.of("grenade", "grenade_launcher", "grenadelauncher", "gl").contains(nativeType)
                || Set.of("40mm", "35mm", "35x32mm").contains(ammoPath) || ammoPath.contains("grenade")
                || Set.of("m320", "m203", "m79", "mgl_40mm", "qlu11").contains(gunPath);
    }

    static HeavyWeaponInfo fromNative(String ammoId, BulletData bullet) {
        var explosion = bullet.getExplosionData();
        boolean explosive = explosion != null && explosion.isExplode();
        var extra = bullet.getExtraDamage();
        double directDamage = extra != null && extra.getDamageAdjust() != null
                ? extra.getDamageAdjust().getFirst().getDamage() : bullet.getDamageAmount();
        return new HeavyWeaponInfo(ammoId, directDamage, explosive ? explosion.getDamage() : 0,
                explosive ? explosion.getRadius() : 0, explosive && explosion.isDestroyBlock());
    }
}
