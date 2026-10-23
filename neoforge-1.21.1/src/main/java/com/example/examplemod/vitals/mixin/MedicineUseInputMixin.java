package com.example.examplemod.vitals.mixin;

import com.example.examplemod.vitals.client.MedicineClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
abstract class MedicineUseInputMixin {
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean taczPubg$sustainAuthorizedMedicine(KeyMapping mapping) { return MedicineClient.useKeyDown(mapping); }
}
