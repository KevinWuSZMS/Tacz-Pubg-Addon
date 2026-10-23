package com.example.examplemod.throwables;

import java.util.UUID;
import me.xjqsh.lrtactical.entity.SmokeGrenadeEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** LR owns every smoke particle and its release sound. We only retain round lifetime boundaries. */
public final class PubgSmokeGrenadeEntity extends SmokeGrenadeEntity implements ItemSupplier {
    private static final EntityDataAccessor<Boolean> PAUSED =
            SynchedEntityData.defineId(PubgSmokeGrenadeEntity.class, EntityDataSerializers.BOOLEAN);
    private UUID session;
    private long map;

    @SuppressWarnings("unchecked")
    public PubgSmokeGrenadeEntity(EntityType<? extends PubgSmokeGrenadeEntity> type, Level level) {
        super((EntityType<SmokeGrenadeEntity>) (EntityType<?>) type, level);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder); builder.define(PAUSED, false);
    }
    public void initialize(LivingEntity owner, ItemStack item, int fuse, UUID session, long map) {
        setOwner(owner); setItem(item.copyWithCount(1)); this.session = session; this.map = map;
        var nativeData = LrThrowableBridge.data(ThrowableKind.SMOKE);
        if (nativeData != null) setBaseData(nativeData.getEntityData());
        ThrowableBallistics.configure(this, ThrowableKind.SMOKE);
        // LR releases at tick 40. Offset the clock to honor the configured/cooked delay while
        // leaving LR's release, sound and cloud implementation untouched.
        tickCount = 40 - Math.max(1, fuse);
        setLife(Math.max(41, ThrowableSettingsService.profile(ThrowableKind.SMOKE).fieldTicks()));
    }
    @Override protected Item getDefaultItem() { return ThrowableRegistration.ITEMS.get(ThrowableKind.SMOKE).get(); }
    public UUID session() { return session; }
    public long mapId() { return map; }
    public boolean emitting() { return tickCount >= 40 && tickCount < getLife(); }
    @Override public void tick() {
        if (level() instanceof ServerLevel level) {
            if (!ThrowableService.scopeExists(level, session, map, position())) { discard(); return; }
            entityData.set(PAUSED, ThrowableService.paused(level, session));
        }
        if (!entityData.get(PAUSED)) super.tick();
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PubgSmokeAge", tickCount); tag.putLong("PubgMap", map);
        if (session != null) tag.putUUID("PubgSession", session);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        var nativeData = LrThrowableBridge.data(ThrowableKind.SMOKE);
        if (nativeData != null) setBaseData(nativeData.getEntityData());
        ThrowableBallistics.configure(this, ThrowableKind.SMOKE);
        setLife(Math.max(41, tag.getInt("Life")));
        tickCount = tag.getInt("PubgSmokeAge"); map = tag.getLong("PubgMap");
        session = tag.hasUUID("PubgSession") ? tag.getUUID("PubgSession") : null;
    }
    @Override public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        super.writeSpawnData(buffer); buffer.writeInt(tickCount);
    }
    @Override public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        super.readSpawnData(buffer); tickCount = buffer.readInt();
    }
}
