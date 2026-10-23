package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.client.reload.WorkbenchMagazineReloadClient;
import com.example.examplemod.gunbalance.client.reload.WorkbenchReloadAnimationRate;
import com.example.examplemod.gunbalance.reload.MagazineReloadTiming;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import com.tacz.guns.api.client.animation.ObjectAnimationSoundChannel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Per-runner timing also covers third-person local sound-only updates. */
@Mixin(value = ObjectAnimationRunner.class, remap = false)
public abstract class ObjectAnimationRunnerMagazineReloadMixin implements WorkbenchReloadAnimationRate {
    @Unique private double taczPubg$reloadAnimationRate = 1.0;

    @Override
    public double taczPubg$getReloadAnimationRate() {
        if (!GunExperimentState.clientMagazineEnabled()) taczPubg$reloadAnimationRate = 1.0;
        return taczPubg$reloadAnimationRate;
    }

    @Override
    public void taczPubg$setReloadAnimationRate(double rate) {
        taczPubg$reloadAnimationRate = GunExperimentState.clientMagazineEnabled()
                && MagazineReloadTiming.accelerated(rate) ? rate : 1.0;
    }

    @ModifyVariable(method = "updateProgress(J)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, remap = false)
    private long taczPubg$advanceReloadAnimation(long elapsed) {
        return MagazineReloadTiming.elapsed(elapsed, WorkbenchMagazineReloadClient.runnerRate(this));
    }

    @Redirect(method = {"update(Z)V", "updateSoundOnly()V"},
            at = @At(value = "INVOKE", target = "Lcom/tacz/guns/api/client/animation/ObjectAnimationSoundChannel;playSound(DDLnet/minecraft/world/entity/Entity;IFF)V"),
            remap = false)
    private void taczPubg$playTrackedReloadSoundClips(ObjectAnimationSoundChannel channel, double from, double to,
            Entity entity, int distance, float volume, float pitch) {
        WorkbenchMagazineReloadClient.playAnimationSounds(channel, from, to, entity, distance, volume, pitch, this);
    }
}
