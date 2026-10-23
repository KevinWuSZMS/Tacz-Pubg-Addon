package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchBoundaryService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Arrow impact backs the arrow up with setPosRaw; this offset is movement, not teleportation. */
@Mixin(AbstractArrow.class)
public abstract class MatchBoundaryArrowImpactMixin {
    @WrapOperation(method = "onHitBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;setPosRaw(DDD)V"))
    private void taczPubg$constrainImpactBackoff(AbstractArrow arrow, double x, double y, double z, Operation<Void> original) {
        Vec3 requested = new Vec3(x - arrow.getX(), y - arrow.getY(), z - arrow.getZ());
        Vec3 constrained = MatchBoundaryService.constrain(arrow, requested);
        original.call(arrow, requested == constrained ? x : arrow.getX() + constrained.x,
                y, requested == constrained ? z : arrow.getZ() + constrained.z);
    }
}
