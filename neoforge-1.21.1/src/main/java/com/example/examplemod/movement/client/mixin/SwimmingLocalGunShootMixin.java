package com.example.examplemod.movement.client.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LocalPlayerShoot.class, remap = false)
public abstract class SwimmingLocalGunShootMixin {
    @Shadow @Final private LocalPlayer player;
    @Shadow @Final private LocalPlayerDataHolder data;

    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$blockSwimmingShot(CallbackInfoReturnable<ShootResult> callback) {
        if (!SwimmingGunRestrictions.blocked(player)) return;
        data.isCharging = false;
        data.chargeProgress = 0;
        callback.setReturnValue(ShootResult.FORGE_EVENT_CANCEL);
    }

    @Inject(method = "chargeShoot", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$cancelSwimmingCharge(boolean charging, CallbackInfoReturnable<Boolean> callback) {
        if (!SwimmingGunRestrictions.blocked(player)) return;
        // Covers charging, delayed charge shots and HOLD release on entering the water.
        data.isCharging = false;
        data.chargeProgress = 0;
        callback.setReturnValue(false);
    }
}
