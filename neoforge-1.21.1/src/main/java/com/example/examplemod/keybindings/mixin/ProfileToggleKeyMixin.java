package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.ToggleKeyMapping;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToggleKeyMapping.class)
abstract class ProfileToggleKeyMixin implements BindingInput.ToggleMode {
    @Shadow @Final private BooleanSupplier needsToggle;
    @Override public boolean taczPubg$isToggleMode() { return needsToggle.getAsBoolean(); }
    @Inject(method = "setDown", at = @At("HEAD"), cancellable = true)
    private void taczPubg$oneTogglePerPress(boolean value, CallbackInfo ci) {
        if (needsToggle.getAsBoolean() && BindingInput.repeatedToggle((KeyMapping) (Object) this, value)) ci.cancel();
    }
}
