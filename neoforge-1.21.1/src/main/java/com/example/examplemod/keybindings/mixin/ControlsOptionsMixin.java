package com.example.examplemod.keybindings.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Movement behavior belongs to each binding profile; the controls landing page retains its three entries. */
@Mixin(ControlsScreen.class)
public abstract class ControlsOptionsMixin {
    @Inject(method = "options", at = @At("HEAD"), cancellable = true)
    private static void tacz_pubg$profileOptions(Options options, CallbackInfoReturnable<OptionInstance<?>[]> callback) {
        callback.setReturnValue(new OptionInstance<?>[]{options.operatorItemsTab()});
    }
}
