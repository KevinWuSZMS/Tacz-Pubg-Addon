package com.example.examplemod.zones.match.flight;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Temporary invisible transport. Vanilla passengers provide shared movement and networking. */
public final class TransportFormationEntity extends Entity {
    private static final EntityDataAccessor<CompoundTag> SEATS = SynchedEntityData.defineId(
            TransportFormationEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private Vec3 interpolationTarget;
    private int interpolationSteps;

    public TransportFormationEntity(EntityType<? extends TransportFormationEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SEATS, new CompoundTag());
    }
    public void assign(List<UUID> participants) {
        CompoundTag seats = new CompoundTag();
        for (int i = 0; i < participants.size(); i++) seats.putInt(participants.get(i).toString(), i);
        entityData.set(SEATS, seats);
    }
    @Override protected boolean canAddPassenger(Entity entity) {
        return entityData.get(SEATS).contains(entity.getUUID().toString());
    }
    @Override public boolean shouldRiderSit() { return false; }
    @Override public Vec3 getPassengerRidingPosition(Entity passenger) {
        int slot = entityData.get(SEATS).getInt(passenger.getUUID().toString());
        double angle = Math.toRadians(getYRot());
        return position().add(FlightGeometry.offset(slot, -Math.sin(angle), Math.cos(angle)));
    }
    @Override protected void positionRider(Entity passenger, Entity.MoveFunction move) {
        Vec3 at = getPassengerRidingPosition(passenger);
        move.accept(passenger, at.x, at.y, at.z);
        passenger.fallDistance = 0;
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide && interpolationSteps > 0) {
            setPos(position().lerp(interpolationTarget, 1D / interpolationSteps--));
        }
        setDeltaMovement(Vec3.ZERO);
        for (Entity passenger : getPassengers()) positionRider(passenger);
    }
    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        interpolationTarget = new Vec3(x, y, z);
        interpolationSteps = Math.max(1, steps);
        setYRot(yaw);
        setXRot(pitch);
    }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
}
