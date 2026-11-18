package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.PubgInventoryInput;
import com.tacz.guns.client.gameplay.LocalPlayerMelee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LocalPlayerMelee.class, remap = false)
abstract class PubgInventoryMeleeMixin {
    @Inject(method = "melee", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$blockInventoryMelee(CallbackInfo callback) {
        if (PubgInventoryInput.blocksCombat()) callback.cancel();
    }
}
