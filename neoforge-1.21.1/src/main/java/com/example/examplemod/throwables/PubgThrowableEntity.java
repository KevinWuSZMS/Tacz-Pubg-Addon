package com.example.examplemod.throwables;

import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.operatorpermissions.OperatorPermissions;
import com.example.examplemod.operatorpermissions.OperatorPermissionsService;
import com.example.examplemod.zones.match.MatchService;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import me.xjqsh.lrtactical.entity.ThrowableItemEntity;
import me.xjqsh.lrtactical.entity.StunGrenadeEntity;
import me.xjqsh.lrtactical.item.throwable.flash.StunThrowableData;
import me.xjqsh.lrtactical.init.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** LR's collision engine carries PUBG timers; LR itself applies stun and renders smoke. */
public final class PubgThrowableEntity extends ThrowableItemEntity implements ItemSupplier {
    private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(PubgThrowableEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> AGE = SynchedEntityData.defineId(PubgThrowableEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FUSE = SynchedEntityData.defineId(PubgThrowableEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> FIELD = SynchedEntityData.defineId(PubgThrowableEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> FIXED = SynchedEntityData.defineId(PubgThrowableEntity.class, EntityDataSerializers.BOOLEAN);
    private UUID session;
    private UUID throwerId;
    private long map;
    private Vec3 attachment;
    private UUID attachedEntity;
    private Vec3 attachmentOffset = Vec3.ZERO;
    private final Set<BlockPos> fireFloors = new HashSet<>();

    public PubgThrowableEntity(EntityType<? extends PubgThrowableEntity> type, Level level) {
        super(type, level);
        ThrowableBallistics.configure(this);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(KIND, 0); builder.define(AGE, 0); builder.define(FUSE, 100);
        builder.define(FIELD, false); builder.define(FIXED, false);
    }
    public void initialize(ThrowableKind kind, LivingEntity owner, ItemStack item, int fuse, UUID session, long map) {
        entityData.set(KIND, kind.ordinal()); entityData.set(FUSE, fuse);
        throwerId = owner == null ? null : owner.getUUID();
        setOwner(owner); setItem(item.copyWithCount(1)); this.session = session; this.map = map;
        var nativeData = LrThrowableBridge.data(kind);
        if (nativeData != null) setBaseData(nativeData.getEntityData());
        ThrowableBallistics.configure(this, kind);
    }
    @Override protected Item getDefaultItem() { return ThrowableRegistration.ITEMS.get(kind()).get(); }
    public ThrowableKind kind() { return ThrowableKind.byOrdinal(entityData.get(KIND)); }
    public boolean field() { return entityData.get(FIELD); }
    public int fieldAge() { return entityData.get(AGE); }
    public int remainingFuse() { return entityData.get(FUSE); }
    /** Vanilla may no longer resolve the owner when a delayed blast or burn deals damage. */
    public UUID throwerId() { return throwerId; }
    public UUID session() { return session; }
    public long mapId() { return map; }
    public double fieldRadius() {
        if (!field()) return 0;
        var profile = ThrowableSettingsService.profile(kind());
        double growth = profile.fieldGrowthTicks() <= 0 ? 1 : Math.min(1, fieldAge() / profile.fieldGrowthTicks());
        double fade = profile.fieldFadeTicks() <= 0 ? 1
                : Math.min(1, (profile.fieldTicks() - fieldAge()) / profile.fieldFadeTicks());
        return profile.radius() * Math.max(0, Math.min(growth, fade));
    }
    public Vec3 fieldCenter() { return position().add(0, kind() == ThrowableKind.SMOKE ? 1.5 : 0, 0); }
    public void attach(Vec3 point) {
        attachment = point; entityData.set(FIXED, true); setNoGravity(true); setDeltaMovement(Vec3.ZERO); setPos(point);
    }
    @Override public AABB getBoundingBoxForCulling() {
        return field() ? new AABB(fieldCenter(), fieldCenter()).inflate(ThrowableSettingsService.profile(kind()).radius() + 1)
                : super.getBoundingBoxForCulling();
    }
    @Override public boolean shouldRenderAtSqrDistance(double distance) {
        return field() ? distance < 160 * 160 : super.shouldRenderAtSqrDistance(distance);
    }
    @Override public boolean isPickable() { return kind() == ThrowableKind.C4 && !field(); }
    @Override public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && kind() == ThrowableKind.C4 && source.is(DamageTypeTags.IS_EXPLOSION) && amount > 0) {
            discard(); return true;
        }
        return false;
    }
    @Override public BounceResult doMultiBounce(Vec3 movement) {
        var result = super.doMultiBounce(movement);
        return entityData.get(FIXED) && attachment != null ? new BounceResult(attachment, Vec3.ZERO) : result;
    }
    @Override protected void onHit(HitResult hit) {
        if (level().isClientSide || field() || entityData.get(FIXED) || isRemoved()) return;
        if (kind() == ThrowableKind.STICKY && hit instanceof BlockHitResult block) {
            attach(block.getLocation().add(Vec3.atLowerCornerOf(block.getDirection().getNormal()).scale(.04))); return;
        }
        if (kind() == ThrowableKind.STICKY && hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
                && entityHit.getEntity() != getOwner()) {
            Entity target = entityHit.getEntity();
            attachmentOffset = hit.getLocation().subtract(target.position());
            attachedEntity = target.getUUID();
            attach(hit.getLocation());
            return;
        }
        if (kind() == ThrowableKind.MOLOTOV) {
            setPos(hit.getLocation());
            if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
                    && entityHit.getEntity() instanceof LivingEntity target && !target.isInWater()
                    && ThrowableService.mayAffect(this, target)) MolotovBurns.ignite(this, target);
            activate(); return;
        }
        super.onHit(hit);
    }
    @Override public void onDeath(HitResult hit) { if (!level().isClientSide) activate(); }
    @Override public void tick() {
        if (!level().isClientSide) {
            ServerLevel level = (ServerLevel) level();
            // Existing saves used the common entity for smoke. Migrate once to LR's native cloud.
            if (kind() == ThrowableKind.SMOKE) {
                var smoke = new PubgSmokeGrenadeEntity(ThrowableRegistration.SMOKE.get(), level);
                smoke.initialize(getOwner() instanceof LivingEntity living ? living : null, getItem(), remainingFuse(), session, map);
                smoke.setPos(position()); smoke.setDeltaMovement(getDeltaMovement());
                if (field()) smoke.tickCount = 40 + fieldAge();
                if (level.addFreshEntity(smoke)) discard();
                return;
            }
            if (attachedEntity != null) {
                Entity attached = level.getEntity(attachedEntity);
                if (attached != null && !attached.isRemoved()) attach(attached.position().add(attachmentOffset));
            }
            if (!ThrowableService.scopeExists(level, session, map, position())) { discard(); return; }
            if (ThrowableService.paused(level, session)) return;
            if (field()) { tickField(level); return; }
            int fuse = remainingFuse();
            if (kind() != ThrowableKind.MOLOTOV && fuse <= 1) { activate(); return; }
            if (kind() != ThrowableKind.MOLOTOV) entityData.set(FUSE, fuse - 1);
            if (kind() == ThrowableKind.C4 && fuse % (fuse < 60 ? 5 : fuse < 160 ? 10 : 20) == 0)
                level.playSound(null, blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 2.2f, fuse < 80 ? 1.8f : 1.2f);
            if (kind() == ThrowableKind.STICKY && fuse % 20 == 0)
                level.playSound(null, blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, .8f, 1.6f);
        }
        if (field() || entityData.get(FIXED)) {
            baseTick();
            if (attachment != null) setPos(attachment);
            return;
        }
        super.tick();
        if (entityData.get(FIXED) && attachment != null) { setPos(attachment); setDeltaMovement(Vec3.ZERO); }
        // A bottle that misses all terrain still has a bounded entity lifetime.
        if (!level().isClientSide && tickCount > ThrowableSettingsService.profile(kind()).maxLifetimeTicks()) discard();
    }
    private void activate() {
        if (isRemoved() || field() || !(level() instanceof ServerLevel level)) return;
        if (!ThrowableService.scopeExists(level, session, map, position())) { discard(); return; }
        if (kind().explosive()) { ThrowableExplosion.detonate(this, level); discard(); return; }
        if (kind() == ThrowableKind.STUN) { flash(level); discard(); return; }
        if ((kind() == ThrowableKind.MOLOTOV || kind() == ThrowableKind.BLUE_ZONE)
                && level.getFluidState(blockPosition()).is(net.minecraft.tags.FluidTags.WATER)) { discard(); return; }
        entityData.set(FIELD, true); entityData.set(AGE, 0); attach(position());
        if (kind() == ThrowableKind.MOLOTOV) {
            level.playSound(null, blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.5f, .8f);
            rebuildFireFloors();
        } else level.playSound(null, blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.2f, .8f);
    }
    private void flash(ServerLevel level) {
        var original = LrThrowableBridge.stunData();
        var data = new StunThrowableData.StunData((float) ThrowableSettingsService.profile(kind()).radius(),
                original.getBlind(), original.getDeafened());
        playThrowableSound("death", 16, 1);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(data.getRadius()))) {
            if (!ThrowableService.mayAffect(this, target)) continue;
            StunGrenadeEntity.calculateAndApplyEffect(this, target, data);
            if (target.hasEffect(ModEffects.BLIND) || target.hasEffect(ModEffects.DEAFENED)) {
                if (session != null) target.getPersistentData().putUUID("tacz_pubg_flash_session", session);
                else target.getPersistentData().remove("tacz_pubg_flash_session");
            }
        }
    }
    private boolean clearLine(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }
    private void tickField(ServerLevel level) {
        int age = fieldAge() + 1;
        entityData.set(AGE, age);
        if (age >= ThrowableSettingsService.profile(kind()).fieldTicks()) { discard(); return; }
        double radius = fieldRadius();
        if (kind() == ThrowableKind.MOLOTOV) {
            if (fireFloors.isEmpty()) rebuildFireFloors();
            if (age % 8 == 0) {
                int budget = 20;
                for (BlockPos floor : fireFloors) if (budget-- > 0 && Vec3.atCenterOf(floor).distanceToSqr(position()) < radius * radius)
                    level.sendParticles(ParticleTypes.FLAME, floor.getX() + .5, floor.getY() + 1.05, floor.getZ() + .5,
                            1, .35, .08, .35, .01);
            }
        } else if (age % 5 == 0) level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                getX(), getY() + 1, getZ(), 8, radius * .6, radius * .5, radius * .6, 0);
        if (age % Math.max(1, ThrowableSettingsService.profile(kind()).integer("damage_interval_ticks", 20)) != 0) return;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius, radius, radius))) {
            if (!ThrowableService.mayAffect(this, target) || target.position().distanceToSqr(position()) > radius * radius) continue;
            if (kind() == ThrowableKind.BLUE_ZONE) {
                if (target instanceof ServerPlayer p && OperatorPermissionsService.enabled(p, OperatorPermissions.Permission.IMMUNE_BLUE_ZONE)) continue;
                var type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(MatchService.BLUE_ZONE);
                target.hurt(new DamageSource(type, this, getOwner()),
                        (float) ThrowableSettingsService.profile(kind()).blueZoneDamage());
            } else if (!target.isInWater() && onBurningFloor(target)) {
                if (target.fireImmune()) continue;
                MolotovBurns.ignite(this, target);
                // PC 6.2's additional 10 PUBG HP/s on burning ground, converted at 5 HP per MC health.
                // Combine the base burn and standing-fire supplement in one hit; vanilla hurt
                // cooldown would otherwise discard the smaller same-tick base burn.
                target.hurt(MolotovBurns.damage(this), (float) (ThrowableSettingsService.profile(kind()).fireDamage() * 3));
            }
        }
    }
    private boolean onBurningFloor(LivingEntity entity) {
        BlockPos feet = entity.blockPosition();
        return fireFloors.contains(feet.below()) || fireFloors.contains(feet) || fireFloors.contains(feet.below(2));
    }
    private void rebuildFireFloors() {
        if (!(level() instanceof ServerLevel level)) return;
        fireFloors.clear();
        BlockPos seed = floorAt(blockPosition());
        if (seed == null) return;
        var pending = new ArrayDeque<BlockPos>(); pending.add(seed); fireFloors.add(seed);
        while (!pending.isEmpty() && fireFloors.size() < 100) {
            BlockPos floor = pending.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = floorAt(floor.above().relative(direction));
                if (next == null || fireFloors.contains(next)
                        || Vec3.atCenterOf(next).distanceToSqr(position())
                            > Math.pow(ThrowableSettingsService.profile(kind()).radius(), 2)
                        || !ThrowableService.scopeExists(level, session, map, Vec3.atCenterOf(next))
                        || MapResetService.isLocked(level, next)
                        || !clearLine(Vec3.atCenterOf(floor).add(0, .65, 0), Vec3.atCenterOf(next).add(0, .65, 0))) continue;
                fireFloors.add(next); pending.add(next);
            }
        }
    }
    private BlockPos floorAt(BlockPos near) {
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos floor = near.offset(0, dy, 0);
            if (!level().hasChunkAt(floor)) continue;
            if (level().getBlockState(floor).isFaceSturdy(level(), floor, Direction.UP)
                    && level().getBlockState(floor.above()).getCollisionShape(level(), floor.above()).isEmpty()
                    && level().getFluidState(floor.above()).isEmpty()) return floor.immutable();
        }
        return null;
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("PubgKind", kind().ordinal()); tag.putInt("PubgFuse", remainingFuse());
        tag.putInt("PubgAge", fieldAge()); tag.putBoolean("PubgField", field());
        tag.putBoolean("PubgFixed", entityData.get(FIXED)); tag.putLong("PubgMap", map);
        if (session != null) tag.putUUID("PubgSession", session);
        if (attachedEntity != null) {
            tag.putUUID("PubgAttachedEntity", attachedEntity);
            tag.putDouble("PubgAttachX", attachmentOffset.x); tag.putDouble("PubgAttachY", attachmentOffset.y);
            tag.putDouble("PubgAttachZ", attachmentOffset.z);
        }
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        int ordinal = tag.getInt("PubgKind");
        if (ordinal < 0 || ordinal >= ThrowableKind.values().length) { discard(); return; }
        entityData.set(KIND, ordinal);
        super.readAdditionalSaveData(tag);
        throwerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        var nativeData = LrThrowableBridge.data(kind());
        if (nativeData != null) setBaseData(nativeData.getEntityData());
        setLife(-1);
        ThrowableBallistics.configure(this, kind());
        entityData.set(FUSE, Math.clamp(tag.getInt("PubgFuse"), 0, 72000));
        entityData.set(AGE, Math.clamp(tag.getInt("PubgAge"), 0, 72000));
        entityData.set(FIELD, tag.getBoolean("PubgField"));
        map = tag.getLong("PubgMap");
        session = tag.hasUUID("PubgSession") ? tag.getUUID("PubgSession") : null;
        attachedEntity = tag.hasUUID("PubgAttachedEntity") ? tag.getUUID("PubgAttachedEntity") : null;
        attachmentOffset = new Vec3(tag.getDouble("PubgAttachX"), tag.getDouble("PubgAttachY"), tag.getDouble("PubgAttachZ"));
        if (!Double.isFinite(attachmentOffset.lengthSqr()) || attachmentOffset.lengthSqr() > 64) {
            attachedEntity = null; attachmentOffset = Vec3.ZERO;
        }
        if (tag.getBoolean("PubgFixed") || field()) attach(position());
    }
}
