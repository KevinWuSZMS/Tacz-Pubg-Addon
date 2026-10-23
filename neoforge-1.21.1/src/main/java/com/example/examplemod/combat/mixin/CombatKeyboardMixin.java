package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.client.CombatInputClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=KeyboardHandler.class,priority=1400)
abstract class CombatKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void taczPubg$rescueFirst(long window,int key,int scan,int action,int modifiers,CallbackInfo ci) {
        if(CombatInputClient.captureKey(window,key,scan,action))ci.cancel();
    }
}
