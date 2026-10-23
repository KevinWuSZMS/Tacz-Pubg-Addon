package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.example.examplemod.gunbalance.reload.WorkbenchMagazineReload;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Default reload and the installed Lua tick_reload scripts share these two API methods. */
@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class ModernGunMagazineReloadClockMixin {
    @Shadow private ShooterDataHolder dataHolder;
    @Shadow private LivingEntity shooter;

    @Inject(method = "getReloadTime()J", at = @At("RETURN"), cancellable = true, remap = false)
    private void taczPubg$advanceReloadClock(CallbackInfoReturnable<Long> callback) {
        double rate = WorkbenchMagazineReload.snapshot(dataHolder, shooter == null ? null : shooter.level());
        if (MagazineReloadTiming.accelerated(rate)) {
            callback.setReturnValue(MagazineReloadTiming.elapsed(callback.getReturnValue(), rate));
        }
    }

    @ModifyVariable(method = "adjustReloadTime(J)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private long taczPubg$convertScriptTimeAdjustment(long virtualAdjustment) {
        return MagazineReloadTiming.adjustment(virtualAdjustment,
                WorkbenchMagazineReload.snapshot(dataHolder, shooter == null ? null : shooter.level()));
    }
}
