package com.example.examplemod.zones.bombing;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Comparator;

/** Loaded launcher metadata, rather than loose ammo names, determines selectable rockets. */
public final class BombingAmmoCatalog {
    public static final String DEFAULT_AMMO = "tacz:rpg_rocket";
    private static final Set<String> ROCKET_TYPES = Set.of("rpg", "rocket", "rocket_launcher");
    private static final Set<String> GRENADE_AMMO = Set.of("40mm", "35mm", "35x32mm");
    private static final Set<String> GRENADE_LAUNCHERS = Set.of("m320", "m203", "m79", "mgl_40mm", "qlu11");
    private BombingAmmoCatalog() {}

    public static Set<String> ids() {
        return java.util.Collections.unmodifiableSet(new TreeSet<>(launchers().keySet()));
    }

    /** A real launcher display is required by TaCZ before its renderer draws an ammo entity model. */
    public static Map<String, ResourceLocation> launchers() {
        Map<String, ResourceLocation> result = new TreeMap<>();
        var guns = TimelessAPI.getAllCommonGunIndex().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString())).toList();
        for (var entry : guns) {
            CommonGunIndex index = entry.getValue();
            ResourceLocation ammo = index.getGunData().getAmmoId();
            var explosion = index.getBulletData().getExplosionData();
            if (ammo != null && isRocketLauncher(index.getType(), entry.getKey().toString(), ammo.toString())
                    && explosion != null && explosion.isExplode()
                    && TimelessAPI.getCommonAmmoIndex(ammo).isPresent()) {
                result.putIfAbsent(ammo.toString(), entry.getKey());
                if ("tacz:rpg7".equals(entry.getKey().toString())) result.put(ammo.toString(), entry.getKey());
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public static boolean isSupported(String ammoId) { return ammoId != null && ids().contains(ammoId); }

    /** TaCZ also labels grenade launchers rpg; their known caliber/launcher metadata takes precedence. */
    public static boolean isRocketLauncher(String type, String gunId, String ammoId) {
        String normalizedType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String gun = path(gunId), ammo = path(ammoId);
        return ROCKET_TYPES.contains(normalizedType) && !GRENADE_AMMO.contains(ammo)
                && !ammo.contains("grenade") && !GRENADE_LAUNCHERS.contains(gun);
    }

    private static String path(String id) {
        String value = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return value.substring(value.indexOf(':') + 1);
    }
}
