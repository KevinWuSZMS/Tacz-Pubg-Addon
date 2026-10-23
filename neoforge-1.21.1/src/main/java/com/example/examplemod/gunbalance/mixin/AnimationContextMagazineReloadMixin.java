package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.reload.WorkbenchMagazineReloadClient;
import com.tacz.guns.api.client.animation.statemachine.AnimationStateContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AnimationStateContext.class, remap = false)
public abstract class AnimationContextMagazineReloadMixin {
    @ModifyVariable(method = "runAnimation(Ljava/lang/String;IZIF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private float taczPubg$scaleReloadTransition(float transitionTime, String name, int track,
            boolean blending, int playType, float originalTransitionTime) {
        return WorkbenchMagazineReloadClient.transitionTime((AnimationStateContext) (Object) this, name, transitionTime);
    }

    @Inject(method = "runAnimation(Ljava/lang/String;IZIF)V", at = @At("RETURN"), remap = false)
    private void taczPubg$bindReloadRunner(String name, int track, boolean blending,
            int playType, float transitionTime, CallbackInfo callback) {
        WorkbenchMagazineReloadClient.bindRunner((AnimationStateContext) (Object) this, name, track);
    }
}
