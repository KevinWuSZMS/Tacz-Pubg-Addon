package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchBoundaryService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A tick must not hit something outside the map before its final position is constrained. */
@Mixin({AbstractArrow.class, ThrowableProjectile.class, AbstractHurtingProjectile.class, FireworkRocketEntity.class,
        FishingHook.class, LlamaSpit.class, ShulkerBullet.class})
public abstract class MatchBoundaryProjectileCollisionMixin {
    @Inject(method = "tick", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Lnet/minecraft/world/phys/HitResult;"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FishingHook;checkCollision()V")
    }, cancellable = true, require = 1)
    private void taczPubg$stopAtMapEdgeBeforeImpact(CallbackInfo callback) {
        Entity projectile = (Entity) (Object) this;
        if (projectile.isRemoved()) { callback.cancel(); return; }
        Vec3 requested = projectile.getDeltaMovement();
        Vec3 constrained = MatchBoundaryService.constrain(projectile, requested);
        if (requested == constrained) return;
        // Fireworks already called move (or followed their attached player) before this ray.
        // The other projectiles trace first, so finish their allowed displacement at the edge.
        if (!(projectile instanceof FireworkRocketEntity))
            projectile.setPos(projectile.getX() + constrained.x, projectile.getY() + constrained.y, projectile.getZ() + constrained.z);
        projectile.setDeltaMovement(Vec3.ZERO);
        projectile.discard();
        callback.cancel();
    }
}
