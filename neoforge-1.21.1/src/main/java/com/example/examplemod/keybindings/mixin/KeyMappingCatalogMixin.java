package com.example.examplemod.keybindings.mixin;

import com.example.examplemod.keybindings.BindingOwners;
import net.minecraft.client.Options;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ClientHooks.class, remap = false)
public abstract class KeyMappingCatalogMixin {
    @Redirect(method = "onRegisterKeyMappings", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/fml/ModLoader;postEvent(Lnet/neoforged/bus/api/Event;)V"))
    private static void taczPubg$captureMappingOwners(Event posted, Options options) {
        BindingOwners.markVanilla(options);
        // The wrapped API preserves ModLoader.postEvent's priority phases and container order.
        ModLoader.postEventWithWrapInModOrder((RegisterKeyMappingsEvent) posted,
                (container, event) -> BindingOwners.beforeContainer(options),
                (container, event) -> BindingOwners.afterContainer(options, container));
    }
}
