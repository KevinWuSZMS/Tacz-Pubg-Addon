package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.CombatPayloads;
import com.example.examplemod.combat.CombatService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class CombatPlayerPoseMixin {
    @Inject(method="updatePlayerPose",at=@At("HEAD"),cancellable=true)
    private void taczPubg$downedPose(CallbackInfo ci) {
        Player player=(Player)(Object)this;
        boolean down=player instanceof ServerPlayer server?CombatService.downed(server):player.level().isClientSide&&CombatPayloads.clientDowned(player.getUUID());
        if(down){player.setPose(Pose.SWIMMING);ci.cancel();}
    }
}
