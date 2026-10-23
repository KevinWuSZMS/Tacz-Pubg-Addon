package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.MatchGunInteractionClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The explicitly rebound pickup key wins over other match quick actions while holding a gun. */
@Mixin(value = KeyboardHandler.class, priority = 1100)
abstract class MatchGunKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void taczPubg$unifiedGunInteract(long window, int key, int scan, int action, int modifiers, CallbackInfo ci) {
        if (com.example.examplemod.vitals.client.MedicineClient.captureKey(window, key, scan, action)) { ci.cancel(); return; }
        if (MatchGunInteractionClient.captureKey(window, key, scan, action)) ci.cancel();
    }
}
