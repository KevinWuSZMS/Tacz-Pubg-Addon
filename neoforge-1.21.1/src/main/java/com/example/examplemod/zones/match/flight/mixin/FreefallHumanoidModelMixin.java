package com.example.examplemod.zones.match.flight.mixin;

import com.example.examplemod.zones.match.flight.client.FreefallPose;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla gliding head/limb animation also applies to the player's armor model. */
@Mixin(HumanoidModel.class)
public abstract class FreefallHumanoidModelMixin {
    @WrapOperation(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getFallFlyingTicks()I"))
    private int taczPubg$freefallModelTicks(LivingEntity entity, Operation<Integer> original) {
        int ticks = FreefallPose.animationTicks(entity);
        return ticks > 0 ? ticks : original.call(entity);
    }
}
