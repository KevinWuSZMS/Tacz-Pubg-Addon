package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
public abstract class ContainerProtectionMixin {
    @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectExtraction(int slot, int count, CallbackInfoReturnable<ItemStack> ci) {
        if (MapResetService.containerLocked((Container) this)) ci.setReturnValue(ItemStack.EMPTY);
    }
    @Inject(method = "removeItemNoUpdate", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectTake(int slot, CallbackInfoReturnable<ItemStack> ci) {
        if (MapResetService.containerLocked((Container) this)) ci.setReturnValue(ItemStack.EMPTY);
    }
    @Inject(method = "setItem", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectInsertion(int slot, ItemStack stack, CallbackInfo ci) {
        if (MapResetService.containerLocked((Container) this)) ci.cancel();
    }
    @Inject(method = "clearContent", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectClear(CallbackInfo ci) {
        if (MapResetService.containerLocked((Container) this)) ci.cancel();
    }
}
