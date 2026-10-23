package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

/** WorldEdit-style two-corner authoring wand; selecting never changes world blocks. */
public final class ItemSpawnAreaStick extends Item {
    private final com.example.examplemod.lootconfig.LootTier tier;
    public com.example.examplemod.lootconfig.LootTier tier() { return tier; }
    public ItemSpawnAreaStick(Properties properties) {
        this(properties, com.example.examplemod.lootconfig.LootTier.HIGH);
    }
    public ItemSpawnAreaStick(Properties properties, com.example.examplemod.lootconfig.LootTier tier) {
        super(properties); this.tier = tier;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return selectSecond(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return selectSecond(context);
    }

    private InteractionResult selectSecond(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) {
            SpawnAreaService.selectSecond(player, context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Texts.component(tier.label() + Texts.tr("message.tacz_pubg.item_spawn_area_stick.tier_loot_quantity_multiplier") + tier.quantityMultiplier()));
        tooltip.add(Component.translatable("item.tacz_pubg.item_spawn_area_stick.tooltip"));
        tooltip.add(Component.translatable("item.tacz_pubg.item_spawn_area_stick.confirm"));
    }
}
