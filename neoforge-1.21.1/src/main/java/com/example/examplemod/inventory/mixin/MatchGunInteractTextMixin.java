package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.MatchGunInteractionClient;
import com.tacz.guns.client.gui.overlay.InteractKeyTextOverlay;
import net.minecraft.client.KeyMapping;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native block/entity hints always show the live pickup binding, including user rebinds. */
@Mixin(value = InteractKeyTextOverlay.class, remap = false)
abstract class MatchGunInteractTextMixin {
    @Redirect(method = {"renderBlockText", "renderEntityText"}, at = @At(value = "FIELD",
            target = "Lcom/tacz/guns/client/input/InteractKey;INTERACT_KEY:Lnet/minecraft/client/KeyMapping;",
            opcode = Opcodes.GETSTATIC, remap = false), remap = false)
    private static KeyMapping taczPubg$showPickupBinding() {
        return MatchGunInteractionClient.displayedBinding();
    }
}
