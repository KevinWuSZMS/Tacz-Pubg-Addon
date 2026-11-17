package com.example.examplemod.combat.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.xjqsh.lrtactical.api.item.IThrowable;
import me.xjqsh.lrtactical.entity.ThrowableItemEntity;
import me.xjqsh.lrtactical.item.ThrowableItem;
import me.xjqsh.lrtactical.item.index.ThrowableIndex;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ThrowableItem.class, remap = false)
abstract class SwimmingLrThrowableMixin {
    @Shadow public abstract void onThrow(Level level, LivingEntity owner, ItemStack stack, ThrowableIndex<?, ?> index);

    @Inject(method = "use", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$noSwimmingUse(Level level, Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> callback) {
        if (SwimmingGunRestrictions.blocked(player)) callback.setReturnValue(InteractionResultHolder.fail(player.getItemInHand(hand)));
    }

    @Inject(method = "onUseTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$dropArmedOnWaterEntry(Level level, LivingEntity owner, ItemStack stack, int remaining, CallbackInfo callback) {
        if (!SwimmingGunRestrictions.blocked(owner)) return;
        if (!level.isClientSide) ((IThrowable) this).getThrowableIndex(stack).ifPresent(index -> onThrow(level, owner, stack, index));
        owner.stopUsingItem();
        callback.cancel();
    }

    @Inject(method = "onThrow", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$keepUnarmedSupply(Level level, LivingEntity owner, ItemStack stack, ThrowableIndex<?, ?> index, CallbackInfo callback) {
        if (SwimmingGunRestrictions.blocked(owner)
                && (!owner.isUsingItem() || owner.getUseItem() != stack || !index.getData().isCookable()
                || owner.getTicksUsingItem() < index.getData().getPrepareTime())) callback.cancel();
    }

    @WrapOperation(method = "onThrow", at = @At(value = "INVOKE",
            target = "Lme/xjqsh/lrtactical/item/index/ThrowableIndex;createEntity(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Lme/xjqsh/lrtactical/entity/ThrowableItemEntity;"), remap = false)
    private ThrowableItemEntity taczPubg$dropInsteadOfThrow(ThrowableIndex<?, ?> index, ItemStack stack,
            LivingEntity owner, Operation<ThrowableItemEntity> original) {
        ThrowableItemEntity projectile = original.call(index, stack, owner);
        // Native onThrow still subtracts the elapsed cooking time and consumes exactly one.
        if (SwimmingGunRestrictions.blocked(owner)) projectile.setDeltaMovement(0, -.05, 0);
        return projectile;
    }
}
