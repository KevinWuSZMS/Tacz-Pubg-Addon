package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyMappingLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = KeyMappingLookup.class, remap = false)
abstract class BindingLookupMixin {
    @Inject(method = "getAll(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void taczPubg$lookup(InputConstants.Key key, boolean releasing, CallbackInfoReturnable<List<KeyMapping>> cir) {
        cir.setReturnValue(BindingInput.lookup(key, releasing, cir.getReturnValue()));
    }
}
