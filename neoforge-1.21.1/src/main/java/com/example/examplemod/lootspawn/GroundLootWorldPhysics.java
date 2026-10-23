package com.example.examplemod.lootspawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Loaded-block collision collection and bounded recovery for the original small carrier. */
public final class GroundLootWorldPhysics {
    private static final double SKIN = 1e-6;
    private static final double RECOVERY_RADIUS = 1.5;
    private static final int MAX_QUERY_CELLS = 32_768;
    private static final double LIMIT = 30_000_001;

    private GroundLootWorldPhysics() {}

    /** Resolve the carrier's initial position without advancing physics. */
    public static void prepareSpawn(GroundLootEntity entity) {
        entity.refreshCollisionBounds();
        AABB body = entity.getBoundingBox();
        List<AABB> nearby = blockers(entity, body.inflate(SKIN));
        if (nearby == null) { block(entity); return; }
        if (overlaps(body, nearby)) {
            nearby = blockers(entity, body.inflate(RECOVERY_RADIUS));
            Vec3 move = nearby == null ? null : recovery(body, entity.position(), nearby);
            if (move == null) { block(entity); return; }
            entity.setPos(entity.position().add(move));
        }
        entity.setPhysicsState(GroundLootEntity.FALLING);
        entity.setOldPosAndRot();
    }

    public static void tick(GroundLootEntity entity) {
        if (entity.isPhysicsBlocked() && entity.tickCount % 10 != 0) return;
        entity.refreshCollisionBounds();
        AABB bounds = entity.getBoundingBox();
        Vec3 velocity = entity.getDeltaMovement();
        if (!finite(velocity)) velocity = Vec3.ZERO;
        List<AABB> collision = blockers(entity, GroundLootPhysics.sweepBounds(bounds,
                GroundLootPhysics.fallingVelocity(velocity)));
        if (collision == null) { stop(entity); return; }
        if (overlaps(bounds, collision)) {
            List<AABB> nearby = blockers(entity, bounds.inflate(RECOVERY_RADIUS));
            if (nearby == null) { stop(entity); return; }
            Vec3 recovery = recovery(bounds, entity.position(), nearby);
            if (recovery == null) {
                block(entity);
                return;
            }
            entity.setPos(entity.position().add(recovery));
            bounds = entity.getBoundingBox();
            collision = blockers(entity, GroundLootPhysics.sweepBounds(bounds,
                    GroundLootPhysics.fallingVelocity(velocity)));
            if (collision == null) { stop(entity); return; }
        }

        GroundLootPhysics.Step step = GroundLootPhysics.step(bounds, velocity, collision);
        entity.setPos(entity.position().add(step.movement()));
        entity.setOnGround(step.grounded());
        entity.setDeltaMovement(step.grounded() ? Vec3.ZERO : step.velocity());
        entity.setPhysicsState(step.grounded() ? GroundLootEntity.RESTING : GroundLootEntity.FALLING);
        if (step.movement().lengthSqr() > 1e-14 || velocity.distanceToSqr(entity.getDeltaMovement()) > 1e-14)
            entity.hasImpulse = true;
    }

    /** No force-loading: missing chunk columns and world-border planes are solid boundaries. */
    public static List<AABB> blockers(Entity entity, AABB query) {
        double cells = (Math.ceil(query.getXsize()) + 2) * (Math.ceil(query.getYsize()) + 2)
                * (Math.ceil(query.getZsize()) + 2);
        if (!Double.isFinite(cells) || cells > MAX_QUERY_CELLS) return null;
        Level level = entity.level();
        List<AABB> result = new ArrayList<>();
        for (var shape : level.getBlockCollisions(entity, query)) for (AABB box : shape.toAabbs())
            if (box.intersects(query)) result.add(box.intersect(query));
        int minX = (int) Math.floor(query.minX / 16), maxX = (int) Math.floor(query.maxX / 16);
        int minZ = (int) Math.floor(query.minZ / 16), maxZ = (int) Math.floor(query.maxZ / 16);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!level.hasChunk(x, z)) result.add(new AABB(x * 16.0, -LIMIT, z * 16.0,
                    x * 16.0 + 16, LIMIT, z * 16.0 + 16));
        }
        var border = level.getWorldBorder();
        result.add(new AABB(-LIMIT, -LIMIT, -LIMIT, border.getMinX(), LIMIT, LIMIT));
        result.add(new AABB(border.getMaxX(), -LIMIT, -LIMIT, LIMIT, LIMIT, LIMIT));
        result.add(new AABB(-LIMIT, -LIMIT, -LIMIT, LIMIT, LIMIT, border.getMinZ()));
        result.add(new AABB(-LIMIT, -LIMIT, border.getMaxZ(), LIMIT, LIMIT, LIMIT));
        return result;
    }

    /** Only move to a nearby free space that the old small carrier can actually reach. */
    public static Vec3 recovery(AABB body, Vec3 origin, List<AABB> blockers) {
        TreeSet<Double> xs = new TreeSet<>(), ys = new TreeSet<>(), zs = new TreeSet<>();
        xs.add(0.0); ys.add(0.0); zs.add(0.0);
        for (AABB block : blockers) if (body.deflate(SKIN).intersects(block)) {
            add(xs, block.minX - body.maxX - SKIN); add(xs, block.maxX - body.minX + SKIN);
            // The small-carrier sweep below rejects crossing a floor or ceiling.
            add(ys, block.maxY - body.minY + SKIN);
            add(ys, block.minY - body.maxY - SKIN);
            add(zs, block.minZ - body.maxZ - SKIN); add(zs, block.maxZ - body.minZ + SKIN);
        }
        List<Vec3> candidates = new ArrayList<>();
        for (double x : nearest(xs)) for (double y : nearest(ys)) for (double z : nearest(zs)) {
            Vec3 move = new Vec3(x, y, z);
            if (move.lengthSqr() > 0 && move.lengthSqr() <= RECOVERY_RADIUS * RECOVERY_RADIUS) candidates.add(move);
        }
        candidates.sort(Comparator.comparingDouble(Vec3::lengthSqr));
        AABB carrier = new AABB(origin.x - .11, origin.y, origin.z - .11,
                origin.x + .11, origin.y + .13, origin.z + .11);
        if (overlaps(carrier, blockers)) return null;
        for (Vec3 move : candidates) {
            if (overlaps(body.move(move), blockers)) continue;
            Vec3 allowed = GroundLootPhysics.sweep(carrier, move, blockers).movement();
            if (allowed.distanceToSqr(move) < 1e-12) return move;
        }
        return null;
    }

    private static boolean overlaps(AABB body, List<AABB> boxes) {
        AABB inner = body.deflate(SKIN);
        return boxes.stream().anyMatch(inner::intersects);
    }

    private static void add(TreeSet<Double> values, double candidate) {
        if (Double.isFinite(candidate) && Math.abs(candidate) <= RECOVERY_RADIUS) values.add(candidate);
    }

    private static List<Double> nearest(TreeSet<Double> values) {
        return values.stream().sorted(Comparator.comparingDouble(Math::abs)).limit(5).toList();
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static void stop(GroundLootEntity entity) {
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setPhysicsState(GroundLootEntity.FALLING);
    }

    private static void block(GroundLootEntity entity) {
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setPhysicsState(GroundLootEntity.BLOCKED);
        entity.setOnGround(false);
        entity.setOldPosAndRot();
    }
}
