package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.LeanState;
import com.example.examplemod.lean.LeanStateAccess;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class LeanPlayerMixin implements LeanStateAccess {
    @Unique private static final EntityDataAccessor<Integer> TACZ_PUBG_LEAN_SEQUENCE = SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);
    @Unique private static final EntityDataAccessor<Byte> TACZ_PUBG_LEAN_TARGET = SynchedEntityData.defineId(Player.class, EntityDataSerializers.BYTE);
    @Unique private static final EntityDataAccessor<Float> TACZ_PUBG_LEAN_FRACTION = SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);
    @Unique private static final EntityDataAccessor<Float> TACZ_PUBG_LEAN_LIMIT = SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);
    @Unique private final LeanState taczPubg$lean = new LeanState();

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void taczPubg$defineLean(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(TACZ_PUBG_LEAN_SEQUENCE, -1); builder.define(TACZ_PUBG_LEAN_TARGET, (byte) 0);
        builder.define(TACZ_PUBG_LEAN_FRACTION, 0F); builder.define(TACZ_PUBG_LEAN_LIMIT, 1F);
    }
    @Override public LeanState taczPubg$leanState() { return taczPubg$lean; }
    @Override public int taczPubg$leanSequence() { return ((Player) (Object) this).getEntityData().get(TACZ_PUBG_LEAN_SEQUENCE); }
    @Override public int taczPubg$leanTarget() { return ((Player) (Object) this).getEntityData().get(TACZ_PUBG_LEAN_TARGET); }
    @Override public float taczPubg$leanFraction() { return ((Player) (Object) this).getEntityData().get(TACZ_PUBG_LEAN_FRACTION); }
    @Override public float taczPubg$leanLimit() { return ((Player) (Object) this).getEntityData().get(TACZ_PUBG_LEAN_LIMIT); }
    @Override public void taczPubg$leanMetadata(int sequence, int target, float fraction, float limit) {
        var data = ((Player) (Object) this).getEntityData();
        data.set(TACZ_PUBG_LEAN_SEQUENCE, sequence); data.set(TACZ_PUBG_LEAN_TARGET, (byte) target);
        data.set(TACZ_PUBG_LEAN_FRACTION, fraction); data.set(TACZ_PUBG_LEAN_LIMIT, limit);
    }
}
