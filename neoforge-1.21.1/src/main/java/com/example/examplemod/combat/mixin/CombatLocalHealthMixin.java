package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.CombatPayloads;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DBNO bleeding updates native health silently; real hits use vanilla damage-event feedback. */
@Mixin(LocalPlayer.class)
abstract class CombatLocalHealthMixin {
    @Unique private boolean taczPubg$wasDownedHealth;

    @Inject(method="hurtTo",at=@At("HEAD"),cancellable=true)
    private void taczPubg$downedHealth(float health,CallbackInfo ci) {
        LocalPlayer player=(LocalPlayer)(Object)this;
        boolean downed=CombatPayloads.clientDowned(player.getUUID());
        // A successful rescue can lower the native value to 10%; that transition is not a hit.
        boolean silent=downed||taczPubg$wasDownedHealth;
        taczPubg$wasDownedHealth=downed;
        if(silent) {
            player.setHealth(health);
            ci.cancel();
        }
    }
}
