package com.example.examplemod.throwables.client.mixin;

import com.example.examplemod.throwables.client.ThrowableClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class ThrowableKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void taczPubg$matchActions(long window, int key, int scan, int action, int modifiers, CallbackInfo ci) {
        if (ThrowableClient.captureKey(window, key, scan, action, modifiers)) ci.cancel();
    }
}
