package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.examplemod.lootconfig.LootConfigModel.Entry;

/** First-stage generation pools; classification uses supplied semantic metadata only. */
public enum LootCategory {
    GUN("gun", Texts.tr("screen.tacz_pubg.item_picker.guns"), .26),
    AMMO("ammo", Texts.tr("screen.tacz_pubg.item_picker.ammo"), 0),
    SCOPE("scope", Texts.tr("message.tacz_pubg.loot_category.scopes"), .06),
    MAGAZINE("magazine", Texts.tr("message.tacz_pubg.loot_category.magazines"), .06),
    MUZZLE("muzzle", Texts.tr("message.tacz_pubg.loot_category.muzzles"), .05),
    GRIP("grip", Texts.tr("message.tacz_pubg.loot_category.grips"), .04),
    STOCK("stock", Texts.tr("message.tacz_pubg.loot_category.stocks"), .02),
    LASER("laser", Texts.tr("message.tacz_pubg.loot_category.lasers"), .01),
    OTHER_ATTACHMENT("other_attachment", Texts.tr("message.tacz_pubg.loot_category.other_attachments"), 0),
    HELMET("helmet", Texts.tr("screen.tacz_pubg.loot_category.helmets"), .06),
    VEST("vest", Texts.tr("screen.tacz_pubg.loot_category.vests"), .06),
    BACKPACK("backpack", Texts.tr("screen.tacz_pubg.loot_category.backpacks"), .06),
    THROWABLE("throwable", Texts.tr("screen.tacz_pubg.loot_category.throwables"), .12),
    MEDICAL("medical", Texts.tr("screen.tacz_pubg.loot_category.medical"), .20),
    ITEM("item", Texts.tr("screen.tacz_pubg.item_picker.other_items"), 0);

    public static final Set<String> IDS = java.util.Arrays.stream(values()).map(LootCategory::id).collect(Collectors.toUnmodifiableSet());
    private static final Map<String, Double> DEFAULTS = createDefaults();
    private final String id;
    private final String label;
    private final double defaultProbability;

    LootCategory(String id, String label, double defaultProbability) {
        this.id = id; this.label = label; this.defaultProbability = defaultProbability;
    }
    public String id() { return id; }
    public String label() { return label; }
    public double defaultProbability() { return defaultProbability; }

    public static Map<String, Double> defaultProbabilities() { return DEFAULTS; }

    public static LootCategory fromId(String id) {
        for (LootCategory category : values()) if (category.id.equals(id)) return category;
        return null;
    }

    /** A laser occupying GRIP remains LASER when its semantic metadata says laser. */
    public static LootCategory classify(Entry entry, String semanticCategory) {
        if (entry == null) return ITEM;
        return switch (entry.kind() == null ? "" : entry.kind()) {
            case "gun" -> GUN;
            case "ammo" -> AMMO;
            case "item" -> itemCategory(entry.id());
            case "attachment" -> switch (semanticCategory == null ? "" : semanticCategory.toLowerCase(java.util.Locale.ROOT)) {
                case "scope" -> SCOPE; case "magazine", "extended_mag" -> MAGAZINE;
                case "muzzle" -> MUZZLE; case "grip" -> GRIP; case "stock" -> STOCK;
                case "laser" -> LASER; default -> OTHER_ATTACHMENT;
            };
            default -> ITEM;
        };
    }

    /** Exact registry IDs: similarly named third-party items stay in the generic pool. */
    public static LootCategory itemCategory(String id) {
        if (id == null) return ITEM;
        return switch (id) {
            case "tacz_pubg:helmet_1", "tacz_pubg:helmet_2", "tacz_pubg:helmet_3" -> HELMET;
            case "tacz_pubg:vest_1", "tacz_pubg:vest_2", "tacz_pubg:vest_3" -> VEST;
            case "tacz_pubg:backpack_1", "tacz_pubg:backpack_2", "tacz_pubg:backpack_3" -> BACKPACK;
            case "tacz_pubg:frag_grenade", "tacz_pubg:smoke_grenade", "tacz_pubg:stun_grenade",
                    "tacz_pubg:molotov", "tacz_pubg:sticky_bomb", "tacz_pubg:c4", "tacz_pubg:blue_zone_grenade" -> THROWABLE;
            case "tacz_pubg:bandage", "tacz_pubg:first_aid_kit", "tacz_pubg:med_kit",
                    "tacz_pubg:energy_drink", "tacz_pubg:painkiller", "tacz_pubg:adrenaline_syringe" -> MEDICAL;
            default -> ITEM;
        };
    }

    private static Map<String, Double> createDefaults() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (LootCategory category : values()) result.put(category.id, category.defaultProbability);
        return Collections.unmodifiableMap(result);
    }
}
