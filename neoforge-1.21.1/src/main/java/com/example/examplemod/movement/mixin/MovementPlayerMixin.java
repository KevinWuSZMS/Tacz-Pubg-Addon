package com.example.examplemod.movement.mixin;

import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MatchSwimming;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.movement.MovementStateAccess;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class MovementPlayerMixin implements MovementStateAccess {
    @Unique private static final EntityDataAccessor<Byte> TACZ_PUBG_GROUND_MODE =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BYTE);
    @Unique private static final EntityDataAccessor<Boolean> TACZ_PUBG_MATCH_PARTICIPANT =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.BOOLEAN);

    @Shadow protected abstract boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose pose);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void taczPubg$defineMovement(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(TACZ_PUBG_GROUND_MODE, (byte) 0);
        builder.define(TACZ_PUBG_MATCH_PARTICIPANT, false);
    }

    @Override public byte taczPubg$movementMode() {
        return ((Player) (Object) this).getEntityData().get(TACZ_PUBG_GROUND_MODE);
    }

    @Override public void taczPubg$movementMode(byte value, boolean acknowledge) {
        ((Player) (Object) this).getEntityData().set(TACZ_PUBG_GROUND_MODE, value, acknowledge);
    }

    @Override public boolean taczPubg$matchParticipant() {
        return ((Player) (Object) this).getEntityData().get(TACZ_PUBG_MATCH_PARTICIPANT);
    }

    @Override public void taczPubg$matchParticipant(boolean value) {
        ((Player) (Object) this).getEntityData().set(TACZ_PUBG_MATCH_PARTICIPANT, value);
    }

    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void taczPubg$waterSwimming(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (!MatchSwimming.controls(player)) return;
        boolean swimming = MatchSwimming.swimming(player);
        if (swimming) MovementService.clear(player);
        player.setSwimming(swimming);
        ci.cancel();
    }

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void taczPubg$groundPose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (MatchSwimming.swimming(player)) {
            MovementService.clear(player);
            player.setSwimming(true);
            if (canPlayerFitWithinBlocksAndEntitiesWhen(Pose.SWIMMING)) player.setPose(Pose.SWIMMING);
            ci.cancel();
            return;
        }
        boolean wading = MatchSwimming.wading(player);
        if (wading) {
            MovementService.reconcileWater(player);
            player.setSwimming(false);
        }
        if (!wading && !MovementService.active(player)) return;
        // TaCZ clears its forcedPose whenever native crawl stops. Our independent ownership also works unarmed.
        Pose requested = switch (MatchSwimming.legalGroundMode(player, MovementService.mode(player))) {
            case CROUCH -> Pose.CROUCHING;
            case PRONE -> Pose.SWIMMING;
            default -> Pose.STANDING;
        };
        // Retain vanilla's collision fallback: standing up cannot expand a body through a ceiling.
        if (canPlayerFitWithinBlocksAndEntitiesWhen(requested)) player.setPose(requested);
        else if (canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING)) player.setPose(Pose.CROUCHING);
        else if (canPlayerFitWithinBlocksAndEntitiesWhen(Pose.SWIMMING)) player.setPose(Pose.SWIMMING);
        ci.cancel();
    }

    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
    private void taczPubg$crouchWithoutEdgeGuard(CallbackInfoReturnable<Boolean> cir) {
        Player player = (Player) (Object) this;
        if (MovementService.active(player) && MovementService.mode(player) == Mode.CROUCH)
            cir.setReturnValue(false);
    }
}
