package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.LeanShots;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import java.util.function.Supplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LivingEntityShoot.class, remap = false)
public abstract class LivingEntityShotRequestMixin {
    @WrapOperation(method = "shoot(Ljava/util/function/Supplier;Ljava/util/function/Supplier;JFZ)Lcom/tacz/guns/api/entity/ShootResult;",
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/api/item/gun/AbstractGunItem;shoot(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Supplier;Ljava/util/function/Supplier;Lnet/minecraft/world/entity/LivingEntity;)V"), remap = false)
    private void taczPubg$associateNativeRequest(AbstractGunItem gun, ShooterDataHolder data, ItemStack stack,
            Supplier<Float> pitch, Supplier<Float> yaw, LivingEntity shooter, Operation<Void> original,
            @Local(argsOnly = true) long timestamp) {
        LeanShots.withRequest(shooter, timestamp, () -> original.call(gun, data, stack, pitch, yaw, shooter));
    }
}
