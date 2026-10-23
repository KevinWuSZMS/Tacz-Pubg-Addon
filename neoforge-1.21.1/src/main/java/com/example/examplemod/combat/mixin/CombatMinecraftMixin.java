package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.client.CombatInputClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class CombatMinecraftMixin {
    @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
    private void taczPubg$noDownedAttack(CallbackInfoReturnable<Boolean> ci){if(CombatInputClient.blocked())ci.setReturnValue(false);}
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void taczPubg$noDownedUse(CallbackInfo ci){if(CombatInputClient.blocked())ci.cancel();}
}
