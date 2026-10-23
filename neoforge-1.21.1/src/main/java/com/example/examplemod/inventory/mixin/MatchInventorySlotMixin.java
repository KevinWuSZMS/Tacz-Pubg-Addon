package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.MatchInventoryService;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class MatchInventorySlotMixin {
    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void pubg$preventDrag(ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (!MatchInventoryService.mayPlace((Slot) (Object) this, stack)) ci.setReturnValue(false);
    }
}
