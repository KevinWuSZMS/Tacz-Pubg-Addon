package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchSpectatorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks Shift detaching and clicking another entity to bypass the server's observer selection. */
@Mixin(ServerPlayer.class)
public abstract class MatchSpectatorPlayerMixin {
    @Inject(method = "setCamera", at = @At("HEAD"), cancellable = true)
    private void taczPubg$lockRoundCamera(Entity camera, CallbackInfo callback) {
        if (!MatchSpectatorService.allowCameraChange((ServerPlayer) (Object) this)) callback.cancel();
    }
}
