package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.EntityBaselineService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class EntityBaselinePassengerMixin {
    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void taczPubg$freezeBaselinePassenger(Entity vehicle, Entity passenger, CallbackInfo callback) {
        if (EntityBaselineService.freezeTick(passenger)) callback.cancel();
    }
}
