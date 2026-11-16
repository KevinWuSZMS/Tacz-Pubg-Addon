package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseHandler.class, priority = 2000)
abstract class PhysicalMouseMixin {
    @Inject(method = "onPress", at = @At("HEAD"))
    private void taczPubg$physicalMouse(long window, int button, int action, int modifiers, CallbackInfo ci) {
        BindingInput.mouse(window, button, action);
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void taczPubg$scrollBinding(long window, double x, double y, CallbackInfo ci) {
        if (BindingInput.scroll(window, y)) ci.cancel();
    }
}
