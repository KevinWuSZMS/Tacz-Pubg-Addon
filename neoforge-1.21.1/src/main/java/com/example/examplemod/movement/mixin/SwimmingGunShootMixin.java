package com.example.examplemod.movement.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import java.util.function.Supplier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LivingEntityShoot.class, remap = false)
public abstract class SwimmingGunShootMixin {
    @Shadow @Final private LivingEntity shooter;
    @Shadow @Final private ShooterDataHolder data;

    // Both ordinary and charged requests converge here, before chambering or script work.
    @Inject(method = "shoot(Ljava/util/function/Supplier;Ljava/util/function/Supplier;JFZ)Lcom/tacz/guns/api/entity/ShootResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$blockSwimmingShot(Supplier<Float> pitch, Supplier<Float> yaw, long timestamp,
            float chargeProgress, boolean hasChargeContext, CallbackInfoReturnable<ShootResult> callback) {
        if (SwimmingGunRestrictions.blocked(shooter)) {
            data.chargeProgress = 0;
            callback.setReturnValue(ShootResult.FORGE_EVENT_CANCEL);
        }
    }
}
