package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.ballistics.BallisticTrajectory;
import com.example.examplemod.gunbalance.ballistics.ExperimentalBallisticProjectile;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.item.ModernKineticGunItem;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Both TaCZ's default Gaussian spread and scripted calcSpread consume this one launch-speed argument. */
@Mixin(value = ModernKineticGunItem.class, remap = false)
public abstract class ModernKineticGunItemBallisticsMixin {
    @ModifyVariable(method = "doBulletSpread(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/projectile/Projectile;IFFFF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private float taczPubg$normalizeRealMuzzleSpeed(float processedSpeed,
            ShooterDataHolder dataHolder, ItemStack gunItem, LivingEntity shooter, Projectile projectile,
            int bulletCount, float originalSpeed, float inaccuracy, float pitch, float yaw) {
        if (!(projectile instanceof ExperimentalBallisticProjectile ballistic)) return processedSpeed;
        ballistic.taczPubg$lockExperimentalBallistics(GunExperimentState.enabled(shooter.level()));
        return ballistic.taczPubg$usesExperimentalBallistics()
                ? BallisticTrajectory.cancelGlobalSpeedMultiplier(processedSpeed, AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get())
                : processedSpeed;
    }
}
