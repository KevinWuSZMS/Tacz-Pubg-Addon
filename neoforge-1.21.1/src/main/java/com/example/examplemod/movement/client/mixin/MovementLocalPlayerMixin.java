package com.example.examplemod.movement.client.mixin;

import com.example.examplemod.movement.client.MovementClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
abstract class MovementLocalPlayerMixin {
    @Shadow private int autoJumpTime;
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void taczPubg$beforeGroundInput(CallbackInfo ci) {
        MovementClient.beforeAiStep((LocalPlayer) (Object) this);
        if (MovementClient.consumesJump() || MovementClient.swimmingContext()) {
            autoJumpTime = 0;
            ((MovementPlayerAccess) this).taczPubg$setJumpTriggerTime(0);
        }
    }
    @Inject(method = "aiStep", at = @At("RETURN"))
    private void taczPubg$afterGroundInput(CallbackInfo ci) {
        MovementClient.afterAiStep((LocalPlayer) (Object) this);
    }
    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V"))
    private void taczPubg$swimmingBeforeTravel(CallbackInfo ci) {
        MovementClient.beforeTravel((LocalPlayer) (Object) this);
    }
    @Redirect(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean taczPubg$groundSprintKey(KeyMapping mapping) { return MovementClient.nativeSprint(mapping); }
    @Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
    private void taczPubg$fixedSprintMode(CallbackInfoReturnable<Boolean> cir) {
        if (!MovementClient.canStartSprint()) cir.setReturnValue(false);
    }
}
