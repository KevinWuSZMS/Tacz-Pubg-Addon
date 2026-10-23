package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paused single-player still calls tickPaused on its server thread, without ticking the game world. */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMaintenanceMixin {
    @Inject(method = "tickPaused", at = @At("TAIL"))
    private void taczPubg$maintainPausedMaps(CallbackInfo callback) {
        var server = (IntegratedServer) (Object) this;
        MapResetService.tickMaintenance(server);
    }
}
