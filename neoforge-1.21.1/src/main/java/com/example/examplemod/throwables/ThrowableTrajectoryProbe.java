package com.example.examplemod.throwables;

import com.example.examplemod.throwables.mixin.ProjectilePreviewAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import me.xjqsh.lrtactical.entity.ThrowableItemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Unspawned, reusable collision probe. LR owns every bounce; this subclass records hits without
 * calling block callbacks, sounds, damage, particles, Entity.tick or any world mutation.
 */
public final class ThrowableTrajectoryProbe extends ThrowableItemEntity {
    public static final int MAX_TICKS = 120, MAX_POINTS = 512;
    public static final double MAX_DISTANCE = 96;
    public enum End { FUSE, IMPACT, ATTACHED, LIMIT }
    public record Point(Vec3 position, Vec3 normal, boolean impact) {}
    public record Prediction(List<Point> points, End end) {}
    private final ArrayList<Point> points = new ArrayList<>();
    private ThrowableKind kind = ThrowableKind.FRAG;
    private Predicate<Entity> observedEntity = entity -> true;
    private Predicate<Vec3> scope = position -> true;
    private Vec3 origin = Vec3.ZERO, stoppedAt, normal = new Vec3(0, 1, 0);
    private End end;
    private int fuse, maxTicks = MAX_TICKS;

    public ThrowableTrajectoryProbe(Level level) {
        // Same registered dimensions as the real projectile, without adding this object to the world.
        super(ThrowableRegistration.THROWABLE.get(), level);
        ThrowableBallistics.configure(this);
    }
    public void reset(ThrowableKind kind, LivingEntity owner, int remainingFuse, Predicate<Entity> observedEntity) {
        reset(kind, owner, remainingFuse, observedEntity, owner.getEyePosition(), owner.getXRot(), owner.getYRot());
    }
    /** Frame sampling changes the launch only; every subsequent bounce still belongs to LR. */
    public void reset(ThrowableKind kind, LivingEntity owner, int remainingFuse, Predicate<Entity> observedEntity,
            Vec3 eye, float pitch, float yaw) {
        this.kind = kind;
        this.fuse = Math.max(1, remainingFuse);
        this.observedEntity = observedEntity;
        this.end = null;
        this.stoppedAt = null;
        this.normal = new Vec3(0, 1, 0);
        this.maxTicks = Math.min(MAX_TICKS, ThrowableSettingsService.profile(kind).maxLifetimeTicks());
        points.clear();
        setNoGravity(false);
        var nativeData = ThrowableSettingsService.nativeData(kind);
        if (nativeData != null) setBaseData(nativeData.getEntityData());
        ThrowableBallistics.configure(this, kind);
        ((ProjectilePreviewAccessor) (Object) this).pubg$setLeftOwner(false);
        ThrowableBallistics.launch(this, owner, false, kind, eye, pitch, yaw);
        origin = position();
        points.add(new Point(origin, normal, false));
    }
    public Prediction predict() {
        for (int tick = 0; tick < maxTicks && end == null; tick++) advance();
        if (end == null) end = End.LIMIT;
        return new Prediction(List.copyOf(points), end);
    }
    /** One real LR motion step, exposed for initialized-world comparison against actual entities. */
    public void advance() {
        if (end != null) return;
        if (!scope.test(position())) { end = End.LIMIT; return; }
        if (kind != ThrowableKind.MOLOTOV && fuse <= 1) { end = End.FUSE; return; }
        if (kind != ThrowableKind.MOLOTOV) fuse--;
        if (!finite(getDeltaMovement()) || getY() < level().getMinBuildHeight() - 64
                || position().distanceToSqr(origin) > MAX_DISTANCE * MAX_DISTANCE) {
            end = End.LIMIT; return;
        }
        var projectileState = (ProjectilePreviewAccessor) (Object) this;
        if (!projectileState.pubg$getLeftOwner())
            projectileState.pubg$setLeftOwner(projectileState.pubg$checkLeftOwner());
        // The native method only changes this probe's fluid flags/velocity; suppress the one
        // outward effect below. This also preserves current pushing and NeoForge fluid handling.
        updateInWaterStateAndDoFluidPushing();
        boolean inWater = isInWater();
        var moved = super.doMultiBounce(getDeltaMovement());
        if (stoppedAt != null) {
            setPos(stoppedAt);
            setDeltaMovement(Vec3.ZERO);
        } else if (end != End.LIMIT) {
            setPos(moved.location());
            // LR tick applies drag after collision resolution, then gravity. The casts are
            // intentional: its 0.99F/0.8F coefficients have float precision.
            Vec3 velocity = moved.deltaMovement().scale(inWater ? .8F : .99F);
            setDeltaMovement(velocity.add(0, -getThrowableGravity(), 0));
        }
        append(position(), normal, false);
    }
    public int remainingFuse() { return fuse; }
    public End end() { return end; }
    public void setScope(Predicate<Vec3> scope) { this.scope = scope; }
    private void append(Vec3 position, Vec3 normal, boolean impact) {
        if (points.size() >= MAX_POINTS) { end = End.LIMIT; return; }
        points.add(new Point(position, normal, impact));
    }
    @Override protected boolean canHitEntity(Entity entity) {
        return super.canHitEntity(entity) && observedEntity.test(entity);
    }
    @Override public HitResult getHitResult(Vec3 start, Vec3 finish, Vec3 movement,
            Predicate<Entity> filter, Level level) {
        if (end != null || stoppedAt != null) return miss(start);
        if (!finite(start) || !finite(finish) || finish.distanceToSqr(origin) > MAX_DISTANCE * MAX_DISTANCE
                || !loadedSegment(level, start, finish)) {
            end = End.LIMIT;
            return miss(start);
        }
        return super.getHitResult(start, finish, movement, filter, level);
    }
    @Override protected void onHit(HitResult hit) {
        if (end != null || hit.getType() == HitResult.Type.MISS) return;
        normal = hit instanceof BlockHitResult block ? Vec3.atLowerCornerOf(block.getDirection().getNormal())
                : getDeltaMovement().normalize().scale(-1);
        append(hit.getLocation(), normal, true);
        if (kind == ThrowableKind.STICKY) {
            stoppedAt = hit.getLocation().add(hit instanceof BlockHitResult ? normal.scale(.04) : Vec3.ZERO);
            end = End.ATTACHED;
        } else if (kind == ThrowableKind.MOLOTOV) {
            stoppedAt = hit.getLocation();
            end = End.IMPACT;
        }
        // Deliberately do not call LR onHit: it invokes block callbacks, sound, and entity damage.
    }
    @Override protected void doWaterSplashEffect() {}
    public static boolean loadedSegment(Level level, Vec3 start, Vec3 finish) {
        if (!finite(start) || !finite(finish) || start.distanceToSqr(finish) > MAX_DISTANCE * MAX_DISTANCE) return false;
        int minX = Mth.floor(Math.min(start.x, finish.x)) >> 4, maxX = Mth.floor(Math.max(start.x, finish.x)) >> 4;
        int minZ = Mth.floor(Math.min(start.z, finish.z)) >> 4, maxZ = Mth.floor(Math.max(start.z, finish.z)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (!level.hasChunkAt(new BlockPos(x << 4, 0, z << 4))) return false;
        return true;
    }
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private static BlockHitResult miss(Vec3 start) {
        return BlockHitResult.miss(start, Direction.UP, BlockPos.containing(start));
    }
}
