package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.PubgInventoryInput;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Final gate also covers charge release and addon/controller paths that bypass ShootKey. */
@Mixin(value = LocalPlayerShoot.class, remap = false)
abstract class PubgInventoryShootMixin {
    @Shadow @Final private LocalPlayerDataHolder data;

    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$blockInventoryShot(CallbackInfoReturnable<ShootResult> callback) {
        if (!PubgInventoryInput.blocksCombat()) return;
        data.isCharging = false;
        data.chargeProgress = 0;
        callback.setReturnValue(ShootResult.FORGE_EVENT_CANCEL);
    }

    @Inject(method = "chargeShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$blockInventoryCharge(boolean charging, CallbackInfoReturnable<Boolean> callback) {
        if (!PubgInventoryInput.blocksCombat()) return;
        data.isCharging = false;
        data.chargeProgress = 0;
        callback.setReturnValue(false);
    }
}
