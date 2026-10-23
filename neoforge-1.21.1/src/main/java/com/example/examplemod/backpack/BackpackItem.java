package com.example.examplemod.backpack;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;

public final class BackpackItem extends Item implements Equipable {
    private final int tier;
    public BackpackItem(Properties properties, int tier) { super(properties); this.tier = tier; }
    public int tier() { return tier; }
    @Override public EquipmentSlot getEquipmentSlot() { return EquipmentSlot.LEGS; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide) BackpackService.equipHand(player, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.tacz_pubg.backpack.capacity", BackpackRules.bonus(tier) / BackpackRules.UNIT));
        lines.add(Component.translatable("tooltip.tacz_pubg.backpack.equip"));
    }
}
