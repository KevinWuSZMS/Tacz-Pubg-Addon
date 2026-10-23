package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchSpectatorService;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Validate on the game thread, including clients that do not obey the spectator input screen. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MatchSpectatorConnectionMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleTeleportToEntityPacket", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void taczPubg$blockObserverTeleport(ServerboundTeleportToEntityPacket packet, CallbackInfo callback) {
        if (MatchSpectatorService.locked(player)) callback.cancel();
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void taczPubg$blockObserverMovement(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        if (MatchSpectatorService.locked(player)) callback.cancel();
    }
}
