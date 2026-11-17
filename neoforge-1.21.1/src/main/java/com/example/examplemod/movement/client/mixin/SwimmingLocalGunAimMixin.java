package com.example.examplemod.movement.client.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.client.gameplay.LocalPlayerAim;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LocalPlayerAim.class, remap = false)
public abstract class SwimmingLocalGunAimMixin {
    @Shadow @Final private LocalPlayer player;
    @Shadow @Final private LocalPlayerDataHolder data;
    @Shadow public abstract void aim(boolean aiming);

    @ModifyVariable(method = "aim", at = @At("HEAD"), argsOnly = true, remap = false)
    private boolean taczPubg$rejectSwimmingAim(boolean aiming) {
        return aiming && !SwimmingGunRestrictions.blocked(player);
    }

    @Inject(method = "tickAimingProgress", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$leaveAimWhenSwimming(CallbackInfo callback) {
        if (!SwimmingGunRestrictions.blocked(player)) return;
        if (data.clientIsAiming) aim(false);
        data.clientIsAiming = false;
        data.clientAimingProgress = 0;
        LocalPlayerDataHolder.oldAimingProgress = 0;
        data.clientAimingTimestamp = System.currentTimeMillis();
        data.isCharging = false;
        data.chargeProgress = 0;
        callback.cancel();
    }
}
