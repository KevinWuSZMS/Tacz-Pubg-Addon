package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchSpectatorService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerList.class)
public abstract class MatchSpectatorRespawnMixin {
    @WrapOperation(method = "respawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/level/portal/DimensionTransition;"))
    private DimensionTransition taczPubg$respawnAtDeath(ServerPlayer player, boolean keepEverything,
            DimensionTransition.PostDimensionTransition after, Operation<DimensionTransition> original) {
        DimensionTransition transition = MatchSpectatorService.respawnTransition(player, keepEverything);
        return transition == null ? original.call(player, keepEverything, after) : transition;
    }
}
