package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.MatchGunInteractionClient;
import com.tacz.guns.client.event.ClientPreventGunClick;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ClientPreventGunClick.class, remap = false)
abstract class MatchGunClickMixin {
    @Redirect(method = "onClickInput", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/KeyMapping;isDown()Z", remap = false), remap = false)
    private static boolean taczPubg$pickupIsInteractModifier(KeyMapping original) {
        return MatchGunInteractionClient.interactionModifierDown(original);
    }
}
