package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Gun-family stage below the main gun loot pool. */
public enum GunClass {
    RIFLE("rifle", Texts.tr("message.tacz_pubg.gun_class.assault_rifles")), SMG("smg", Texts.tr("message.tacz_pubg.gun_class.smgs")), MG("mg", Texts.tr("message.tacz_pubg.gun_class.machine_guns")),
    SHOTGUN("shotgun", Texts.tr("message.tacz_pubg.gun_class.shotguns")), SNIPER("sniper", Texts.tr("message.tacz_pubg.gun_class.sniper_rifles")), DMR("dmr", Texts.tr("message.tacz_pubg.gun_class.dmrs")),
    PISTOL("pistol", Texts.tr("message.tacz_pubg.gun_class.handguns")), HEAVY("heavy", Texts.tr("screen.tacz_pubg.zone_settings.heavy_weapons")), OTHER("other", Texts.tr("message.tacz_pubg.gun_class.other_guns"));

    public static final Set<String> IDS = Arrays.stream(values()).map(GunClass::id).collect(Collectors.toUnmodifiableSet());
    private final String id, label;
    GunClass(String id, String label) { this.id = id; this.label = label; }
    public String id() { return id; }
    public String label() { return label; }
    public static GunClass byId(String id) {
        for (GunClass value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.gun_class.select_valid_gun_class", id));
    }
    public static GunClass classify(String nativeType, boolean reviewedDmr) {
        if (reviewedDmr) return DMR;
        return switch (nativeType == null ? "" : nativeType.toLowerCase(Locale.ROOT)) {
            case "rifle", "ar", "assault_rifle" -> RIFLE;
            case "smg", "submachine_gun" -> SMG;
            case "mg", "lmg", "machine_gun", "machinegun" -> MG;
            case "shotgun", "sg" -> SHOTGUN;
            case "sniper", "sr" -> SNIPER;
            case "dmr", "marksman", "marksman_rifle" -> DMR;
            case "pistol", "handgun" -> PISTOL;
            case "rpg", "rocket", "rocket_launcher", "grenade", "grenade_launcher", "grenadelauncher", "gl", "launcher", "heavy", "heavy_weapon" -> HEAVY;
            default -> OTHER;
        };
    }
}
