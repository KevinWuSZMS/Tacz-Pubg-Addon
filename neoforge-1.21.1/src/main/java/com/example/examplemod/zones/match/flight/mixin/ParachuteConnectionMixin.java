package com.example.examplemod.zones.match.flight.mixin;

import com.example.examplemod.zones.match.flight.MatchFlightService;
import com.example.examplemod.zones.match.flight.ParachuteItem;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep long, legitimate canopy descents from accumulating vanilla's floating timeout. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ParachuteConnectionMixin {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void taczPubg$acceptParachuteDescent(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        // Vanilla has already applied its speed, collision and position checks. Do not exempt
        // hovering, ascent, nonparticipants, or players who took their temporary equipment off.
        if (packet.hasPosition() && clientIsFloating && !player.onGround()
                && player.getKnownMovement().y < -0.03125
                && ParachuteItem.isEquipped(player) && MatchFlightService.descentStage(player) > 0)
            clientIsFloating = false;
    }
}
