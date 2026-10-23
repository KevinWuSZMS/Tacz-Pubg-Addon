package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.zones.bombing.BombingPolicy;
import com.example.examplemod.zones.bombing.BombingProjectile;
import com.example.examplemod.zones.bombing.BombingService;
import com.example.examplemod.gunbalance.ballistics.ExperimentalBallisticProjectile;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.TacHitResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the service's marked instance is intercepted; player-fired rockets keep TaCZ's full native behavior. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class EntityKineticBulletBombingMixin implements BombingProjectile {
    @Shadow private ResourceLocation ammoId;
    @Shadow private ResourceLocation gunId;
    @Shadow private int life;
    @Shadow private float gravity;
    @Shadow private float friction;
    @Shadow private Vec3 startPos;
    @Shadow private boolean explosion;
    @Shadow private float explosionDamage;
    @Shadow private float explosionRadius;
    @Shadow private int explosionDelayCount;
    @Shadow private int pierce;

    @Override public void taczPubg$configureBombingProjectile(ResourceLocation ammo, ResourceLocation launcher, Vec3 origin, float damageRadius) {
        ammoId = ammo;
        gunId = launcher;
        life = BombingPolicy.MAX_LIFE_TICKS;
        gravity = .06F;
        friction = 0;
        startPos = origin;
        explosion = true;
        explosionDamage = 0; // All damage is handled once by the scoped environmental explosion.
        explosionRadius = damageRadius;
        explosionDelayCount = Integer.MAX_VALUE;
        pierce = 1;
        ((ExperimentalBallisticProjectile) this).taczPubg$lockExperimentalBallistics(false);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$limitBombingLifetime(CallbackInfo callback) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        if (!self.level().isClientSide() && BombingService.isBombing(self) && !BombingService.canTick(self)) {
            self.discard(); callback.cancel();
        }
    }

    @Inject(method = "onBulletTick()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$continuousVerticalBombingCollision(CallbackInfo callback) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        if (!self.level().isClientSide() && BombingService.isBombing(self)) {
            BombingService.fallingTick(self); callback.cancel();
        }
    }

    @Inject(method = "onHitEntity(Lcom/tacz/guns/util/TacHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$environmentalEntityImpact(TacHitResult hit, Vec3 start, Vec3 end, CallbackInfo callback) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        if (BombingService.isBombing(self)) { BombingService.detonate(self, hit.getLocation()); callback.cancel(); }
    }

    @Inject(method = "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void taczPubg$environmentalBlockImpact(BlockHitResult hit, Vec3 start, Vec3 end, CallbackInfo callback) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        if (BombingService.isBombing(self)) { BombingService.hitBlock(self, hit); callback.cancel(); }
    }
}
