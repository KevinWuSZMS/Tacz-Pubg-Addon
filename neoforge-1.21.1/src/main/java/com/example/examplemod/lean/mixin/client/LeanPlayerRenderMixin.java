package com.example.examplemod.lean.mixin.client;

import java.util.ArrayDeque;
import java.util.Deque;

import com.example.examplemod.lean.client.LeanPlayerRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LeanPlayerRenderMixin {
    @Shadow protected EntityModel<?> model;
    @Unique private final Deque<LeanPlayerRender.Frame> taczPubg$leanFrames = new ArrayDeque<>();

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void taczPubg$beginLeanFrame(LivingEntity entity, float yaw, float partialTick,
            PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
        if (entity instanceof Player && model instanceof PlayerModel<?>) {
            taczPubg$leanFrames.push(new LeanPlayerRender.Frame(pose));
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V", shift = At.Shift.AFTER))
    private void taczPubg$poseUpperBody(LivingEntity entity, float yaw, float partialTick,
            PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
        if (entity instanceof Player player && model instanceof PlayerModel<?> playerModel) {
            taczPubg$leanFrames.peek().apply(player, playerModel, pose, partialTick);
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void taczPubg$restoreLeanFrame(LivingEntity entity, float yaw, float partialTick,
            PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
        if (entity instanceof Player && model instanceof PlayerModel<?>) {
            taczPubg$leanFrames.pop().restore();
        }
    }
}
