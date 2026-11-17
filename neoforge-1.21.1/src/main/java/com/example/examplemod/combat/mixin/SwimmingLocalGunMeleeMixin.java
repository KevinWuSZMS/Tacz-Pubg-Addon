package com.example.examplemod.combat.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import com.tacz.guns.client.gameplay.LocalPlayerMelee;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LocalPlayerMelee.class, remap = false)
abstract class SwimmingLocalGunMeleeMixin {
    @Shadow @Final private LocalPlayer player;

    @Inject(method = "melee", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$noSwimmingMeleeFeedback(CallbackInfo callback) {
        if (SwimmingGunRestrictions.blocked(player)) callback.cancel();
    }
}
