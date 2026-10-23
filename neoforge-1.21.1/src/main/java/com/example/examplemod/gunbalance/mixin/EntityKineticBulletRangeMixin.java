package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.ballistics.BulletRangePolicy;
import com.example.examplemod.gunbalance.ballistics.ExperimentalBallisticProjectile;
import com.tacz.guns.entity.EntityKineticBullet;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Precisely wraps tick's age-only discard; collision, fuse, pierce and void discards remain native. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class EntityKineticBulletRangeMixin {
    @Shadow private Vec3 startPos;
    @Shadow private float friction;
    @Unique private boolean taczPubg$hasRangeOrigin;
    @Unique private double taczPubg$rangeOriginX;
    @Unique private double taczPubg$rangeOriginZ;
    @Unique private final BulletRangePolicy.Progress taczPubg$rangeProgress = new BulletRangePolicy.Progress();

    @Inject(method = "tick()V", at = @At("HEAD"), remap = false)
    private void taczPubg$captureHorizontalOrigin(CallbackInfo callback) {
        if (taczPubg$hasRangeOrigin) return;
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        // Server uses TaCZ's true launch position. Its native spawn packet has no startPos;
        // a client copy (or a direct third-party spawn) falls back to its first ticking position.
        Vec3 origin = startPos != null ? startPos : self.position();
        if (Double.isFinite(origin.x) && Double.isFinite(origin.z)) {
            taczPubg$rangeOriginX = origin.x;
            taczPubg$rangeOriginZ = origin.z;
            taczPubg$hasRangeOrigin = true;
        }
    }

    @Redirect(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/entity/EntityKineticBullet;discard()V"), require = 1, remap = false)
    private void taczPubg$postponeOnlyNativeAgeCleanup(EntityKineticBullet self) {
        if (self.isRemoved()) return;
        Vec3 velocity = self.getDeltaMovement();
        if (!Double.isFinite(self.getY()) || !Double.isFinite(velocity.y)) {
            self.discard();
            return;
        }
        float effectiveFriction = self.isInWater() ? 0.4F : friction;
        boolean quadratic = !self.isInWater() && friction == 0
                && (Object) self instanceof ExperimentalBallisticProjectile ballistic
                && ballistic.taczPubg$usesExperimentalBallistics();
        boolean postpone = taczPubg$hasRangeOrigin && BulletRangePolicy.postponeAgeCleanup(
                self.getX() - taczPubg$rangeOriginX, self.getZ() - taczPubg$rangeOriginZ,
                velocity.x, velocity.z, effectiveFriction, quadratic, taczPubg$rangeProgress);
        if (!postpone) self.discard();
    }
}
