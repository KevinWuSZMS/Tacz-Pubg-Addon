package com.example.examplemod.zones;

import com.example.examplemod.lootconfig.LootConfigPayloads;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Filled-map appearance only: it never allocates vanilla map data or changes terrain. */
public final class PubgConfigMapItem extends Item {
    public PubgConfigMapItem(Properties properties) { super(properties); }
    @Override public boolean isFoil(ItemStack stack) { return true; }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        open(player);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        open(context.getPlayer());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        open(context.getPlayer());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    private void open(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.getCooldowns().isOnCooldown(this)) return;
        // The existing entry point checks OP on the server and reuses the full config snapshot/session.
        LootConfigPayloads.open(serverPlayer);
        player.getCooldowns().addCooldown(this, 10);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tacz_pubg.pubg_config_map.tooltip"));
    }
}
