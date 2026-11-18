package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.PubgInventoryInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also prevent a held UI mouse button from becoming a vanilla attack/use on closing. */
@Mixin(Minecraft.class)
abstract class PubgInventoryCombatMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void taczPubg$inventoryAttack(CallbackInfoReturnable<Boolean> callback) {
        if (PubgInventoryInput.blocksCombat()) callback.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void taczPubg$inventoryHoldAttack(boolean attack, CallbackInfo callback) {
        if (PubgInventoryInput.blocksCombat()) callback.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void taczPubg$inventoryUse(CallbackInfo callback) {
        if (PubgInventoryInput.blocksCombat()) callback.cancel();
    }
}
