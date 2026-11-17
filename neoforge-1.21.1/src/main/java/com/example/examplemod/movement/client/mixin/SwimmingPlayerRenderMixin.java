package com.example.examplemod.movement.client.mixin;

import com.example.examplemod.movement.MatchSwimming;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Limit only the rendered swimmer's body; camera and authoritative look angles stay untouched. */
@Mixin(PlayerRenderer.class)
abstract class SwimmingPlayerRenderMixin {
    @WrapOperation(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    private float taczPubg$limitSwimmingBodyPitch(float amount, float from, float target, Operation<Float> original,
            AbstractClientPlayer player, PoseStack pose, float bob, float bodyYaw, float partialTick, float scale) {
        if (MatchSwimming.swimming(player)) {
            // Use the interpolated view for both local and remote swimmers, including brief
            // native fluid-contact gaps at the surface. Keep vanilla's stance transition.
            target = -90.0F - Mth.clamp(player.getViewXRot(partialTick), -15.0F, 15.0F);
        }
        return original.call(amount, from, target);
    }
}
