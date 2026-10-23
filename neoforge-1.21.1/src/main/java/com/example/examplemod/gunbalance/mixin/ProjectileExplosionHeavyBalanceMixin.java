package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.heavy.HeavyWeaponExplosionPolicy;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProjectile;
import com.tacz.guns.util.block.ProjectileExplosion;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the native explosion while separating approved heavy terrain energy and center impulse. */
@Mixin(value = ProjectileExplosion.class, remap = false)
public abstract class ProjectileExplosionHeavyBalanceMixin {
    @Shadow @Final private Entity exploder;
    @Shadow @Final private float radius;
    @Shadow @Final private float power;

    @Redirect(method = "explode()V", at = @At(value = "FIELD",
            target = "Lcom/tacz/guns/util/block/ProjectileExplosion;radius:F", opcode = Opcodes.GETFIELD, ordinal = 0),
            require = 1, expect = 1, allow = 1, remap = false)
    private float taczPubg$independentHeavyTerrainPower(ProjectileExplosion explosion) {
        if (exploder instanceof HeavyWeaponProjectile shot && shot.taczPubg$isGrenade()) return 3;
        return HeavyWeaponExplosionPolicy.terrainPower(radius,
                exploder instanceof HeavyWeaponProjectile shot ? shot.taczPubg$heavyWeaponProfile() : null);
    }

    @Redirect(method = "explode()V", at = @At(value = "FIELD",
            target = "Lcom/tacz/guns/util/block/ProjectileExplosion;radius:F", opcode = Opcodes.GETFIELD, ordinal = 1),
            require = 1, expect = 1, allow = 1, remap = false)
    private float taczPubg$perGunDamageRadius(ProjectileExplosion explosion) {
        return HeavyWeaponExplosionPolicy.damageRadius(radius,
                exploder instanceof HeavyWeaponProjectile shot ? shot.taczPubg$heavyWeaponProfile() : null);
    }

    @ModifyArg(method = "explode()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), index = 1,
            require = 1, expect = 1, allow = 1, remap = false)
    private float taczPubg$truncatedPowerDamage(float nativeAmount) {
        return HeavyWeaponExplosionPolicy.damageAmount(nativeAmount, power,
                exploder instanceof HeavyWeaponProjectile shot ? shot.taczPubg$heavyWeaponProfile() : null);
    }

    @ModifyVariable(method = "explode()V", at = @At(value = "STORE", ordinal = 0), index = 31,
            require = 1, expect = 1, allow = 1, remap = false)
    private float taczPubg$capHeavyCenterImpulse(float nativeImpulse) {
        return HeavyWeaponExplosionPolicy.clampCenterImpulse(nativeImpulse,
                exploder instanceof HeavyWeaponProjectile shot ? shot.taczPubg$heavyWeaponProfile() : null);
    }
}
