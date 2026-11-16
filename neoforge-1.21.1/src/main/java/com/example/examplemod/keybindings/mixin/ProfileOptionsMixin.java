package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
abstract class ProfileOptionsMixin {
    @Inject(method = "save", at = @At("HEAD"))
    private void taczPubg$saveGeneral(CallbackInfo ci) { BindingInput.beforeOptionsSave(); }
    @Inject(method = "save", at = @At("RETURN"))
    private void taczPubg$restoreActive(CallbackInfo ci) { BindingInput.afterOptionsSave(); }
}
