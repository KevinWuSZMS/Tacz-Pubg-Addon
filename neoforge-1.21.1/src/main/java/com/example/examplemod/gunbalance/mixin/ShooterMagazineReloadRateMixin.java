package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.reload.WorkbenchReloadRate;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShooterDataHolder.class, remap = false)
public abstract class ShooterMagazineReloadRateMixin implements WorkbenchReloadRate {
    @Unique private double taczPubg$magazineReloadRate = 1.0;
    @Unique private ResourceLocation taczPubg$reloadGunId;
    @Unique private ResourceLocation taczPubg$reloadMagazineId;

    @Override public double taczPubg$getMagazineReloadRate() { return taczPubg$magazineReloadRate; }
    @Override public void taczPubg$setMagazineReloadRate(double rate) { taczPubg$magazineReloadRate = rate; }
    @Override public ResourceLocation taczPubg$getReloadGunId() { return taczPubg$reloadGunId; }
    @Override public ResourceLocation taczPubg$getReloadMagazineId() { return taczPubg$reloadMagazineId; }
    @Override public void taczPubg$setReloadMagazineSelection(ResourceLocation gunId, ResourceLocation magazineId) {
        taczPubg$reloadGunId = gunId;
        taczPubg$reloadMagazineId = magazineId;
    }

    @Inject(method = "initialData()V", at = @At("TAIL"), remap = false)
    private void taczPubg$clearMagazineRate(CallbackInfo callback) {
        taczPubg$magazineReloadRate = 1.0;
        taczPubg$reloadGunId = null;
        taczPubg$reloadMagazineId = null;
    }
}
