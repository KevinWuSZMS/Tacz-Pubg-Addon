package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.FirstPersonPoseSafety;
import com.tacz.guns.client.event.FirstPersonRenderGunEvent;
import com.tacz.guns.client.model.BedrockGunModel;
import com.tacz.guns.util.math.SecondOrderDynamics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Local guards for the r7 first-person renderer; no global dynamics or gun-data changes. */
@Mixin(value = FirstPersonRenderGunEvent.class, remap = false)
public abstract class FirstPersonPoseSafetyMixin {
    @Shadow @Final private static SecondOrderDynamics REFIT_OPENING_DYNAMICS;

    @Inject(method = "applyJumpingSway(Lcom/tacz/guns/client/model/BedrockGunModel;F)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void taczPubg$skipUndefinedJumpSample(BedrockGunModel model, float partialTick,
            CallbackInfo callback) {
        if (!FirstPersonPoseSafety.canSampleJumpVelocity(partialTick)) callback.cancel();
    }

    @Redirect(method = "applyFirstPersonGunTransform", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/util/math/SecondOrderDynamics;update(F)F"),
            require = 2, remap = false)
    private static float taczPubg$boundRefitProgress(SecondOrderDynamics dynamics, float target) {
        float smoothed = dynamics.update(target);
        // The same method also samples ADS; retain its original spring behaviour.
        return dynamics == REFIT_OPENING_DYNAMICS
                ? FirstPersonPoseSafety.refitProgress(smoothed, target) : smoothed;
    }
}
