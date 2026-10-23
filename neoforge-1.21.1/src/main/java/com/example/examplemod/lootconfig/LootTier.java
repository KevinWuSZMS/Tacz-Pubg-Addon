package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

/** Five independent loot profiles; multiplier affects quantity, never probability weights. */
public enum LootTier {
    VERY_LOW("very_low", Texts.tr("message.tacz_pubg.loot_tier.very_low"), .25, 0xF2F2F2), LOW("low", Texts.tr("message.tacz_pubg.loot_tier.low"), .5, 0x42B85C),
    MEDIUM("medium", Texts.tr("message.tacz_pubg.loot_tier.medium"), .75, 0x3989ED), HIGH("high", Texts.tr("message.tacz_pubg.loot_tier.high"), 1, 0xA963DC),
    VERY_HIGH("very_high", Texts.tr("message.tacz_pubg.loot_tier.very_high"), 1.25, 0xF39B36);
    private final String id, label;
    private final double quantityMultiplier;
    private final int rgb;
    LootTier(String id, String label, double quantityMultiplier, int rgb) {
        this.id=id; this.label=label; this.quantityMultiplier=quantityMultiplier; this.rgb=rgb;
    }
    public String id() { return id; }
    public String label() { return label; }
    public double quantityMultiplier() { return quantityMultiplier; }
    public int rgb() { return rgb; }
    public static LootTier fromId(String id) {
        for (var tier : values()) if (tier.id.equals(id)) return tier;
        throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_tier.unknown_loot_tier", id));
    }
    public static LootTier fromMultiplier(double value) {
        LootTier closest=HIGH;
        for (var tier : values()) if (Math.abs(tier.quantityMultiplier-value)<Math.abs(closest.quantityMultiplier-value)) closest=tier;
        return closest;
    }
}
