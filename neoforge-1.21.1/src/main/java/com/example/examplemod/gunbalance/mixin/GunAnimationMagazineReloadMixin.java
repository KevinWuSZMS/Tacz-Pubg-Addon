package com.example.examplemod.gunbalance.mixin;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.client.reload.WorkbenchMagazineReloadClient;
import com.example.examplemod.gunbalance.client.reload.WorkbenchReloadAnimationContext;
import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import net.minecraft.world.item.ItemStack;
import org.luaj.vm2.LuaTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reload-only snapshot; the native context clock and bolt ejection parameters stay untouched. */
@Mixin(value = GunAnimationStateContext.class, remap = false)
public abstract class GunAnimationMagazineReloadMixin implements WorkbenchReloadAnimationContext {
    @Shadow private ItemStack currentGunItem;
    @Unique private double taczPubg$reloadAnimationRate = 1.0;
    @Unique private final Set<Integer> taczPubg$reloadAnimationTracks = new HashSet<>();

    @Override
    public ItemStack taczPubg$getReloadAnimationGunItem() {
        return currentGunItem;
    }

    @Override
    public double taczPubg$getReloadAnimationRate() {
        if (!GunExperimentState.clientMagazineEnabled()) taczPubg$clearReloadAnimation();
        return taczPubg$reloadAnimationRate;
    }

    @Override
    public void taczPubg$setReloadAnimationRate(double rate) {
        taczPubg$reloadAnimationRate = GunExperimentState.clientMagazineEnabled()
                && MagazineReloadTiming.accelerated(rate) ? rate : 1.0;
    }

    @Override
    public void taczPubg$beginReloadAnimation(double rate) {
        taczPubg$reloadAnimationTracks.clear();
        taczPubg$setReloadAnimationRate(rate);
    }

    @Override
    public void taczPubg$clearReloadAnimation() {
        taczPubg$reloadAnimationTracks.clear();
        taczPubg$reloadAnimationRate = 1.0;
    }

    @Override
    public void taczPubg$bindReloadAnimationTrack(int track) {
        taczPubg$reloadAnimationTracks.add(track);
    }

    @Override
    public void taczPubg$finishReloadAnimationTrack(int track) {
        if (taczPubg$reloadAnimationTracks.remove(track) && taczPubg$reloadAnimationTracks.isEmpty()) {
            taczPubg$reloadAnimationRate = 1.0;
        }
    }

    @Override
    public void taczPubg$retireReloadAnimationTracks(IntPredicate finished) {
        if (taczPubg$reloadAnimationTracks.removeIf(track -> finished.test(track))
                && taczPubg$reloadAnimationTracks.isEmpty()) {
            taczPubg$reloadAnimationRate = 1.0;
        }
    }

    @Inject(method = "getStateMachineParams()Lorg/luaj/vm2/LuaTable;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void taczPubg$scaleReloadIntroShellTiming(CallbackInfoReturnable<LuaTable> callback) {
        LuaTable original = callback.getReturnValue();
        double rate = WorkbenchMagazineReloadClient.rate((GunAnimationStateContext) (Object) this);
        LuaTable adjusted = WorkbenchMagazineReloadClient.stateMachineParams(original, rate);
        if (adjusted != original) callback.setReturnValue(adjusted);
    }
}
