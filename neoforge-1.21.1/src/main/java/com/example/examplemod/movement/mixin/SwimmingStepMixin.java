package com.example.examplemod.movement.mixin;

import com.example.examplemod.movement.MatchSwimming;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class SwimmingStepMixin {
    @Inject(method = "maxUpStep", at = @At("HEAD"), cancellable = true)
    private void taczPubg$noDeepWaterStep(CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof Player player && MatchSwimming.swimming(player) && !MatchSwimming.canClimbOut(player))
            cir.setReturnValue(0.0F);
    }
}
