package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.PubgInventoryInput;
import com.tacz.guns.client.gameplay.LocalPlayerAim;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LocalPlayerAim.class, remap = false)
abstract class PubgInventoryAimMixin {
    @Shadow @Final private LocalPlayerDataHolder data;
    @Shadow public abstract void aim(boolean aiming);

    @ModifyVariable(method = "aim", at = @At("HEAD"), argsOnly = true, remap = false)
    private boolean taczPubg$blockInventoryAim(boolean aiming) {
        return aiming && !PubgInventoryInput.blocksCombat();
    }

    @Inject(method = "tickAimingProgress", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$exitAimInInventory(CallbackInfo callback) {
        if (!PubgInventoryInput.blocksCombat()) return;
        if (data.clientIsAiming) aim(false);
        data.clientIsAiming = false;
        data.clientAimingProgress = 0;
        LocalPlayerDataHolder.oldAimingProgress = 0;
        callback.cancel();
    }
}
