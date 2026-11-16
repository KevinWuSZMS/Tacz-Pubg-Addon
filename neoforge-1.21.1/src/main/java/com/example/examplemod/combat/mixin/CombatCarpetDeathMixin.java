package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.CombatService;
import com.example.examplemod.zones.match.bot.MatchBotService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carpet otherwise disconnects even when NeoForge cancelled the superclass death for DBNO. */
@Pseudo
@Mixin(targets = "carpet.patches.EntityPlayerMPFake", remap = false)
public abstract class CombatCarpetDeathMixin {
    @Inject(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void taczPubg$retainMatchPlayer(DamageSource source, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (CombatService.downed(player) || MatchBotService.retainAfterDeath(player)) ci.cancel();
    }
}
