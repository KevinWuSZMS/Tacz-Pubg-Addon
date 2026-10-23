package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperProtectionMixin {
    @Inject(method = "tryTakeInItemFromSlot", at = @At("HEAD"), cancellable = true)
    private static void taczPubg$protectPull(Hopper hopper, Container container, int slot, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        if (MapResetService.containerLocked(container) || MapResetService.containerLocked(hopper)) ci.setReturnValue(false);
    }
    @Inject(method = "tryMoveInItem", at = @At("HEAD"), cancellable = true)
    private static void taczPubg$protectPush(Container source, Container destination, ItemStack stack, int slot, Direction direction, CallbackInfoReturnable<ItemStack> ci) {
        if (MapResetService.containerLocked(source) || MapResetService.containerLocked(destination)) ci.setReturnValue(stack);
    }
}
