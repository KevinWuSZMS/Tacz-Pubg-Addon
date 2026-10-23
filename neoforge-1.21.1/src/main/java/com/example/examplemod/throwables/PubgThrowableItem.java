package com.example.examplemod.throwables;

import com.example.examplemod.localization.Texts;
import com.tacz.guns.api.item.IAnimationItem;
import java.util.List;
import me.xjqsh.lrtactical.api.item.IThrowable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public final class PubgThrowableItem extends Item implements IThrowable, IAnimationItem {
    public final ThrowableKind kind;
    public PubgThrowableItem(Properties properties, ThrowableKind kind) { super(properties); this.kind = kind; }
    @Override public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return kind == ThrowableKind.C4 ? ThrowableSettingsService.profile(kind).deploymentTicks() : 72000;
    }
    @Override public ResourceLocation getId(ItemStack stack) { return LrThrowableBridge.id(kind); }
    @Override public java.util.Optional<me.xjqsh.lrtactical.item.index.ThrowableIndex<?, ?>> getThrowableIndex(ItemStack stack) {
        return java.util.Optional.ofNullable(ThrowableSettingsService.nativeIndex(kind));
    }
    @Override public boolean isSame(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem() && IAnimationItem.matchesIgnoreCount(first, second);
    }
    @Override public int getMaxStackSize(ItemStack stack) { return ThrowableSettingsService.profile(kind).stackSize(); }
    @Override public boolean onEntitySwing(ItemStack stack, LivingEntity entity) { return true; }
    @Override public boolean useOnRelease(ItemStack stack) { return true; }
    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.BOW; }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        // LR's native throwable interaction/first-person pipeline is main-hand only.
        if (hand == InteractionHand.OFF_HAND) return InteractionResultHolder.fail(stack);
        if (player instanceof ServerPlayer p && ThrowableService.cooking(p)) return InteractionResultHolder.fail(stack);
        if (player instanceof ServerPlayer p && !ThrowableService.begin(p, this, hand)) {
            p.displayClientMessage(Texts.of("message.tacz_pubg.throwable.unavailable"), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }
    @Override public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        if (entity instanceof ServerPlayer p) ThrowableService.release(p, false);
    }
    @Override public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof ServerPlayer p && kind == ThrowableKind.C4) ThrowableService.deploy(p);
        return stack;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        lines.add(Texts.of(kind == ThrowableKind.C4
                ? "screen.tacz_pubg.throwable.c4_hint" : "screen.tacz_pubg.throwable.throw_hint"));
        if (ThrowableSettingsService.profile(kind).cookable()) lines.add(Texts.of("screen.tacz_pubg.throwable.cook_hint"));
    }
}
