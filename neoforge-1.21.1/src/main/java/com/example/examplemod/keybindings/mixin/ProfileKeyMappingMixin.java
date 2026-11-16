package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingInput;
import com.example.examplemod.keybindings.BindingKeys;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
abstract class ProfileKeyMappingMixin implements IKeyMappingExtension {
    @Inject(method = "matches", at = @At("HEAD"), cancellable = true)
    private void taczPubg$matches(int key, int scan, CallbackInfoReturnable<Boolean> cir) {
        var self = (KeyMapping) (Object) this;
        if (BindingInput.managed(self)) cir.setReturnValue(BindingInput.matches(self, BindingInput.keyboardId(key, scan)));
    }
    @Inject(method = "matchesMouse", at = @At("HEAD"), cancellable = true)
    private void taczPubg$matchesMouse(int button, CallbackInfoReturnable<Boolean> cir) {
        var self = (KeyMapping) (Object) this;
        if (BindingInput.managed(self)) cir.setReturnValue(BindingInput.matches(self, BindingInput.mouseId(button)));
    }
    @Inject(method = "getTranslatedKeyMessage", at = @At("HEAD"), cancellable = true)
    private void taczPubg$bindingLabel(CallbackInfoReturnable<Component> cir) {
        var binding = BindingInput.binding((KeyMapping) (Object) this);
        if (binding != null) cir.setReturnValue(Component.literal(binding.display()));
    }
    @Inject(method = "isUnbound", at = @At("HEAD"), cancellable = true)
    private void taczPubg$unbound(CallbackInfoReturnable<Boolean> cir) {
        var binding = BindingInput.binding((KeyMapping) (Object) this);
        if (binding != null) cir.setReturnValue(binding.isEmpty());
    }
    @Redirect(method = "setAll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setDown(Z)V"))
    private static void taczPubg$restoreChord(KeyMapping mapping, boolean down) { BindingInput.restoreDown(mapping, down); }

    // A concrete override is needed because NeoForge declares these as default interface methods.
    @Override public boolean isActiveAndMatches(InputConstants.Key key) {
        var self = (KeyMapping) (Object) this;
        return BindingInput.managed(self) ? getKeyConflictContext().isActive() && BindingInput.matches(self, BindingKeys.fromKey(key))
                : IKeyMappingExtension.super.isActiveAndMatches(key);
    }
    @Override public boolean isConflictContextAndModifierActive() {
        return BindingInput.managed((KeyMapping) (Object) this) ? getKeyConflictContext().isActive()
                : IKeyMappingExtension.super.isConflictContextAndModifierActive();
    }
}
