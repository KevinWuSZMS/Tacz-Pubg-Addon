package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchBoundaryService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Only vanilla tick movement, never constructors, network interpolation or explicit teleport APIs. */
@Mixin({AbstractArrow.class, ThrowableProjectile.class, AbstractHurtingProjectile.class, FireworkRocketEntity.class,
        FishingHook.class, EyeOfEnder.class, LlamaSpit.class, ShulkerBullet.class})
public abstract class MatchBoundaryProjectilePositionMixin {
    @WrapOperation(method = "tick", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/AbstractHurtingProjectile;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FishingHook;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/EyeOfEnder;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/EyeOfEnder;setPosRaw(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/LlamaSpit;setPos(DDD)V"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ShulkerBullet;setPos(DDD)V")
    }, require = 1)
    private void taczPubg$constrainTickPosition(@Coerce Entity projectile, double x, double y, double z, Operation<Void> original) {
        Vec3 requested = new Vec3(x - projectile.getX(), y - projectile.getY(), z - projectile.getZ());
        Vec3 constrained = MatchBoundaryService.constrain(projectile, requested);
        if (requested != constrained) {
            x = projectile.getX() + constrained.x;
            z = projectile.getZ() + constrained.z;
            projectile.setDeltaMovement(Vec3.ZERO);
        }
        original.call(projectile, x, y, z);
    }
}
