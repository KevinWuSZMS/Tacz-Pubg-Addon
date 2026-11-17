package com.example.examplemod.combat.mixin;

import com.example.examplemod.movement.SwimmingGunRestrictions;
import java.util.List;
import me.xjqsh.lrtactical.api.melee.MeleeAction;
import me.xjqsh.lrtactical.capability.CombatProperties;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CombatProperties.class, remap = false)
abstract class SwimmingLrMeleeMixin {
    @Shadow @Final private Player entity;
    @Shadow @Final private List<?> delayedActions;
    @Shadow private boolean preparingAttack;
    @Shadow public abstract void resetMeleeSync();

    @Inject(method = "preAttack(Lme/xjqsh/lrtactical/api/melee/MeleeAction;ILnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$noSwimmingPreparation(MeleeAction action, int combo, Vec3 origin, Vec3 direction,
            CallbackInfoReturnable<Boolean> callback) {
        if (SwimmingGunRestrictions.blocked(entity)) callback.setReturnValue(false);
    }

    @Inject(method = "postAttack", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$noSwimmingHit(MeleeAction action, int combo, List<Entity> targets, CallbackInfo callback) {
        if (!SwimmingGunRestrictions.blocked(entity)) return;
        resetMeleeSync();
        callback.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void taczPubg$forgetPendingAttackOnWaterEntry(CallbackInfo callback) {
        // Leave draw/consumable state alone. Only pending melee and its delayed lunge are cleared.
        if ((preparingAttack || !delayedActions.isEmpty()) && SwimmingGunRestrictions.blocked(entity)) resetMeleeSync();
    }
}
