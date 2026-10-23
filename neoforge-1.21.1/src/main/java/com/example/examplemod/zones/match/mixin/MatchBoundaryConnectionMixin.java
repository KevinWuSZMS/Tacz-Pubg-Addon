package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchBoundaryService;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla can absMoveTo the packet target after Entity.move. Constrain that target as well.
 * Ordinal 0 feeds vanilla's invalid-value check; ordinal 1 runs on the server thread after
 * both that check and the pending-teleport guard. Y, angles, ground flags and vanilla
 * collision/speed checks remain unchanged.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MatchBoundaryConnectionMixin {
    @Shadow public ServerPlayer player;

    @ModifyExpressionValue(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getX(D)D", ordinal = 1))
    private double taczPubg$constrainPlayerTargetX(double target) {
        return MatchBoundaryService.constrainTargetX(player, target);
    }

    @ModifyExpressionValue(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;getZ(D)D", ordinal = 1))
    private double taczPubg$constrainPlayerTargetZ(double target) {
        return MatchBoundaryService.constrainTargetZ(player, target);
    }

    @ModifyExpressionValue(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;getX()D", ordinal = 1))
    private double taczPubg$constrainVehicleTargetX(double target) {
        return MatchBoundaryService.constrainTargetX(player.getRootVehicle(), target);
    }

    @ModifyExpressionValue(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;getZ()D", ordinal = 1))
    private double taczPubg$constrainVehicleTargetZ(double target) {
        return MatchBoundaryService.constrainTargetZ(player.getRootVehicle(), target);
    }
}
