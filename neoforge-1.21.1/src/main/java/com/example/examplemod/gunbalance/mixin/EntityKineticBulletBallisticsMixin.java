package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.gunbalance.GunExperimentState;
import com.example.examplemod.gunbalance.ballistics.BallisticTrajectory;
import com.example.examplemod.gunbalance.ballistics.BallisticShotMode;
import com.example.examplemod.gunbalance.ballistics.BallisticsProfiles;
import com.example.examplemod.gunbalance.ballistics.ExperimentalBallisticProjectile;
import com.tacz.guns.entity.EntityKineticBullet;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prepares one accurate flight chord for TaCZ's native hit tests, then restores the true next velocity. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class EntityKineticBulletBallisticsMixin implements ExperimentalBallisticProjectile {
    @Shadow private float gravity;
    @Shadow private float friction;
    @Unique private final BallisticShotMode taczPubg$shotMode = new BallisticShotMode();
    @Unique private BallisticsProfiles.Profile taczPubg$ballisticProfile;
    @Unique private BallisticTrajectory.Step taczPubg$flightStep;
    @Unique private Vec3 taczPubg$flightChord;

    @Override
    public void taczPubg$lockExperimentalBallistics(boolean enabled) {
        if (taczPubg$shotMode.isLocked()) return;
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        taczPubg$ballisticProfile = enabled ? BallisticsProfiles.forGun(self.getGunId()) : null;
        taczPubg$shotMode.lock(taczPubg$ballisticProfile != null);
    }

    @Override
    public boolean taczPubg$usesExperimentalBallistics() { return taczPubg$ballisticProfile != null; }

    @Inject(method = "writeSpawnData(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", at = @At("TAIL"), remap = false)
    private void taczPubg$writeShotMode(RegistryFriendlyByteBuf buffer, CallbackInfo callback) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        if (!taczPubg$shotMode.isLocked()) taczPubg$lockExperimentalBallistics(GunExperimentState.enabled(self.level()));
        buffer.writeByte(BallisticShotMode.SPAWN_SCHEMA);
        buffer.writeByte(taczPubg$usesExperimentalBallistics() ? 1 : 0);
    }

    @Inject(method = "readSpawnData(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", at = @At("TAIL"), remap = false)
    private void taczPubg$readShotMode(RegistryFriendlyByteBuf buffer, CallbackInfo callback) {
        boolean experimental = BallisticShotMode.decode(buffer.readUnsignedByte(), buffer.readUnsignedByte());
        if (taczPubg$shotMode.isLocked()) throw new IllegalStateException(Texts.tr("error.tacz_pubg.entity_kinetic_bullet_ballistics_mixin.ballistics_spawn_mode_was_read_more_than_once"));
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        taczPubg$ballisticProfile = experimental ? BallisticsProfiles.forGun(self.getGunId()) : null;
        if (experimental && taczPubg$ballisticProfile == null)
            throw new IllegalStateException(Texts.tr("error.tacz_pubg.entity_kinetic_bullet_ballistics_mixin.client_is_missing_the_server_s_reviewed_ballistic_profile") + self.getGunId());
        taczPubg$shotMode.lock(experimental);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), remap = false)
    private void taczPubg$prepareFlightChord(CallbackInfo callback) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        taczPubg$flightStep = null;
        taczPubg$flightChord = null;
        // Client bullets arrive with final launch velocity in TaCZ's complex spawn payload.
        // The server normally locks in doBulletSpread; this also handles direct third-party spawns.
        if (!taczPubg$shotMode.isLocked()) taczPubg$lockExperimentalBallistics(GunExperimentState.enabled(self.level()));
        if (taczPubg$ballisticProfile == null || self.isInWater() || friction != 0
                || !Float.isFinite(gravity) || gravity < 0) return;
        Vec3 velocity = self.getDeltaMovement();
        if (!Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)) return;
        taczPubg$flightStep = BallisticTrajectory.step(velocity.x, velocity.y, velocity.z, gravity,
                taczPubg$ballisticProfile.effectiveDragPerMeter());
        taczPubg$flightChord = new Vec3(taczPubg$flightStep.dx(), taczPubg$flightStep.dy(), taczPubg$flightStep.dz());
        self.setDeltaMovement(taczPubg$flightChord);
    }

    @Inject(method = "tick()V", at = @At("TAIL"), remap = false)
    private void taczPubg$storeTrueNextVelocity(CallbackInfo callback) {
        if (taczPubg$flightStep == null) return;
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        if (!self.isRemoved()) {
            boolean water = self.isInWater();
            float nativeDrag = water ? 0.4F : friction;
            float nativeGravity = water ? gravity * 0.6F : gravity;
            Vec3 expectedNative = taczPubg$flightChord.scale(1 - nativeDrag).add(0, -nativeGravity, 0);
            // Other mods may change a projectile's motion in hit events. Preserve that intervention.
            if (self.getDeltaMovement().distanceToSqr(expectedNative) < 1e-20) {
                Vec3 next = new Vec3(taczPubg$flightStep.vx(), taczPubg$flightStep.vy(), taczPubg$flightStep.vz());
                // Water entry retains TaCZ's strong water damping and reduced gravity.
                if (water) next = taczPubg$flightChord.scale(1 - 0.4F).add(0, -gravity * 0.6F, 0);
                self.setDeltaMovement(next);
            }
        }
        taczPubg$flightStep = null;
        taczPubg$flightChord = null;
    }
}
