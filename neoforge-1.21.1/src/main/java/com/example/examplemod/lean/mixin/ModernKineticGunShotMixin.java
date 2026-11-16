package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.LeanShots;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class ModernKineticGunShotMixin {
    @Shadow private LivingEntity shooter;

    @WrapOperation(method = "shootOnce", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/util/CycleTaskHelper;addCycleTask(Ljava/util/function/BooleanSupplier;JI)V"), remap = false)
    private void taczPubg$freezeEachRound(BooleanSupplier nativeTask, long period, int cycles, Operation<Void> original) {
        // Capture the shooter now: scripts can reuse their API object before a delayed round runs.
        LivingEntity roundShooter = shooter;
        LeanShots.Request request = LeanShots.currentRequest(roundShooter);
        original.call((BooleanSupplier) () -> LeanShots.withRound(roundShooter, request, nativeTask), period, cycles);
    }

    @ModifyArgs(method = "lambda$shootOnce$2", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/api/item/gun/AbstractGunItem;doBulletSpread(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/projectile/Projectile;IFFFF)V"), remap = false)
    private void taczPubg$useRoundAim(Args arguments) {
        LeanShots.ShotPose pose = LeanShots.current(arguments.get(2));
        if (pose != null) {
            arguments.set(7, pose.pitch());
            arguments.set(8, pose.yaw());
        }
    }
}
