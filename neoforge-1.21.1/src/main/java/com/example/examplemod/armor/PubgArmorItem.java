package com.example.examplemod.armor;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Native equipment slot item; all protection/wear is handled once by ArmorService. */
public final class PubgArmorItem extends ArmorItem {
    private final int tier;
    private final boolean helmet;
    public PubgArmorItem(Properties properties, boolean helmet, int tier) {
        super(ArmorRegistration.MATERIAL, helmet ? Type.HELMET : Type.CHESTPLATE,
                properties.durability(ArmorRules.maximum(helmet, tier) * ArmorRules.PRECISION));
        this.tier = tier; this.helmet = helmet;
    }
    public int tier() { return tier; }
    public boolean helmet() { return helmet; }
    public static boolean vest(ItemStack stack) {
        return stack.getItem() instanceof PubgArmorItem item && !item.helmet;
    }
    public static double health(ItemStack stack) {
        return ArmorRules.remaining(stack.getMaxDamage(), stack.getDamageValue()) / (double) ArmorRules.PRECISION;
    }
    @Override public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, T entity, Consumer<Item> broken) {
        // Vanilla damages every armor slot before computing its armor reduction.
        // Our selected head/body piece is instead worn only after an accepted hit.
        return 0;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.tacz_pubg.armor.health",
                Math.round(health(stack)), ArmorRules.maximum(helmet, tier)));
        tooltip.add(Component.translatable("tooltip.tacz_pubg.armor.protection",
                Math.round((1 - ArmorRules.multiplier(tier, helmet, stack.getDamageValue() >= stack.getMaxDamage())) * 100)));
    }
}
