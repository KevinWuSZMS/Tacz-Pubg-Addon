package com.example.examplemod.zones.match.flight.mixin;

import com.example.examplemod.zones.match.flight.client.FreefallPose;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reuse vanilla's pitch and banking only within the player's render transform. */
@Mixin(PlayerRenderer.class)
public abstract class FreefallPlayerRendererMixin {
    @WrapOperation(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isFallFlying()Z"))
    private boolean taczPubg$freefallRotation(AbstractClientPlayer player, Operation<Boolean> original) {
        return original.call(player) || FreefallPose.active(player);
    }

    @WrapOperation(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getFallFlyingTicks()I"))
    private int taczPubg$freefallRotationTicks(AbstractClientPlayer player, Operation<Integer> original) {
        int ticks = FreefallPose.animationTicks(player);
        return ticks > 0 ? ticks : original.call(player);
    }
}
