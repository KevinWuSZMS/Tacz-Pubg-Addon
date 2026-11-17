package com.example.examplemod.combat.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.entity.shooter.LivingEntityMelee;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LivingEntityMelee.class, remap = false)
abstract class SwimmingGunMeleeMixin {
    @Shadow @Final private LivingEntity shooter;
    @Shadow @Final private ShooterDataHolder data;

    @Inject(method = {"melee", "scheduleTickMelee"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$cancelSwimmingMelee(CallbackInfo callback) {
        if (!SwimmingGunRestrictions.blocked(shooter)) return;
        // A wind-up begun on land cannot resume its pending hit after leaving the water.
        data.meleePrepTickCount = -1;
        callback.cancel();
    }
}
