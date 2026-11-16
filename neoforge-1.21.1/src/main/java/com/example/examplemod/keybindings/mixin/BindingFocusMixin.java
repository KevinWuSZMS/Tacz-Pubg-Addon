package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class BindingFocusMixin {
    @Inject(method = "setWindowActive", at = @At("HEAD"))
    private void taczPubg$releaseOnFocusLoss(boolean focused, CallbackInfo ci) {
        if (!focused) BindingInput.releasePhysical();
    }
}
