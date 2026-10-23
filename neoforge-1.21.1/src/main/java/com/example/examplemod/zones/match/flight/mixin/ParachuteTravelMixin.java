package com.example.examplemod.zones.match.flight.mixin;

import com.example.examplemod.zones.match.flight.FlightMotion;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class ParachuteTravelMixin {
    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void taczPubg$travelWithParachute(Vec3 input, CallbackInfo callback) {
        if (FlightMotion.travel((Player) (Object) this)) callback.cancel();
    }
}
