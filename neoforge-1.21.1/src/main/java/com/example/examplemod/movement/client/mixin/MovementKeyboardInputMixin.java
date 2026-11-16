package com.example.examplemod.movement.client.mixin;

import com.example.examplemod.movement.client.MovementClient;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class MovementKeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void taczPubg$groundControls(boolean slow, float multiplier, CallbackInfo ci) {
        MovementClient.updateInput((Input) (Object) this);
    }
}
