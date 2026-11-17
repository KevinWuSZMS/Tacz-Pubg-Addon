package com.example.examplemod.movement.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.entity.shooter.LivingEntityAim;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEntityAim.class, remap = false)
public abstract class SwimmingGunAimMixin {
    @Shadow @Final private LivingEntity shooter;
    @Shadow @Final private ShooterDataHolder data;

    @ModifyVariable(method = "aim", at = @At("HEAD"), argsOnly = true, remap = false)
    private boolean taczPubg$rejectSwimmingAim(boolean aiming) {
        return aiming && !SwimmingGunRestrictions.blocked(shooter);
    }

    @Inject(method = "tickAimingProgress", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$leaveAimWhenSwimming(CallbackInfo callback) {
        if (!SwimmingGunRestrictions.blocked(shooter)) return;
        // This runs before TaCZ tickSprint, so stale ADS cannot suppress swim sprint.
        data.isAiming = false;
        data.aimingProgress = 0;
        data.chargeProgress = 0;
        data.aimingTimestamp = System.currentTimeMillis();
        callback.cancel();
    }
}
