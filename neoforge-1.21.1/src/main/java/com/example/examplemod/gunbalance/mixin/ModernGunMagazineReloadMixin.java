package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.example.examplemod.gunbalance.reload.WorkbenchMagazineReload;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.item.ModernKineticGunItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ModernKineticGunItem.class, remap = false)
public abstract class ModernGunMagazineReloadMixin {
    @Inject(method = "startReload(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"), remap = false)
    private void taczPubg$snapshotReloadRate(ShooterDataHolder holder, ItemStack gunStack,
            LivingEntity shooter, CallbackInfoReturnable<Boolean> callback) {
        WorkbenchMagazineReload.begin(holder, gunStack, shooter.level());
    }

    @Inject(method = "tickReload(Lcom/tacz/guns/entity/shooter/ShooterDataHolder;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)Lcom/tacz/guns/api/entity/ReloadState;",
            at = @At("RETURN"), remap = false)
    private void taczPubg$syncRealCountdown(ShooterDataHolder holder, ItemStack gunStack,
            LivingEntity shooter, CallbackInfoReturnable<ReloadState> callback) {
        ReloadState result = callback.getReturnValue();
        double rate = WorkbenchMagazineReload.snapshot(holder, shooter.level());
        if (result != null && MagazineReloadTiming.accelerated(rate)) {
            result.setCountDown(MagazineReloadTiming.duration(result.getCountDown(), rate));
        }
    }
}
