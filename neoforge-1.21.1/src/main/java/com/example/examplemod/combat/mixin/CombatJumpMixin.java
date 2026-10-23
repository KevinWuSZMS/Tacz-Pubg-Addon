package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.combat.CombatService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class CombatJumpMixin {
    @Inject(method="jumpFromGround",at=@At("HEAD"),cancellable=true)
    private void taczPubg$noDownedJump(CallbackInfo ci) {
        Object self=this;
        if(self instanceof ServerPlayer server?CombatService.downed(server):self instanceof Player player&&player.level().isClientSide&&CombatPayloads.clientDowned(player.getUUID()))ci.cancel();
    }
}
