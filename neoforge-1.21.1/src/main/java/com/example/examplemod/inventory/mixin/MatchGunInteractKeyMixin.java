package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.MatchGunInteractionClient;
import com.tacz.guns.client.input.InteractKey;
import net.neoforged.neoforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leave the user's TaCZ key configuration intact; only suspend its old listeners during a match. */
@Mixin(value = InteractKey.class, remap = false)
abstract class MatchGunInteractKeyMixin {
    @Inject(method = "onInteractKeyPress", at = @At("HEAD"), cancellable = true, remap = false)
    private static void taczPubg$pickupReplacesKeyboard(InputEvent.Key event, CallbackInfo ci) {
        if (MatchGunInteractionClient.active()) ci.cancel();
    }

    @Inject(method = "onInteractMousePress", at = @At("HEAD"), cancellable = true, remap = false)
    private static void taczPubg$pickupReplacesMouse(InputEvent.MouseButton.Post event, CallbackInfo ci) {
        if (MatchGunInteractionClient.active()) ci.cancel();
    }
}
