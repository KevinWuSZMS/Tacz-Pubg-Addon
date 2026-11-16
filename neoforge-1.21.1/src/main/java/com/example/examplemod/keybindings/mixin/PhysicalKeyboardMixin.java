package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyboardHandler.class, priority = 2000)
abstract class PhysicalKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void taczPubg$physicalKey(long window, int key, int scan, int action, int modifiers, CallbackInfo ci) {
        BindingInput.keyboard(window, key, scan, action);
    }
}
