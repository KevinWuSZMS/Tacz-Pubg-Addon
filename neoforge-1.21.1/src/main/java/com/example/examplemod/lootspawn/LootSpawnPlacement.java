package com.example.examplemod.lootspawn;

import com.example.examplemod.lootconfig.LootSpawnRules.FloorSample;
import com.example.examplemod.spawn.SpawnFloorGeometry;
import com.example.examplemod.spawn.SpawnFloorGeometry.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.random.RandomGenerator;

/** Valid floor support, full model clearance and explicit companion-ammo annulus reservation. */
final class LootSpawnPlacement {
    private static final double EPSILON = 1e-4;
    private static final double[] OFFSETS = {0.2, 0.5, 0.8};

    record Slot(BlockPos floor, double x, double y, double z, float yaw, AABB bounds) {}
    private LootSpawnPlacement() {}
    private record Column(int x, int z) {}
    static final class FloorIndex {
        final List<FloorSample> floors;
        final Map<Column, List<FloorSample>> columns = new HashMap<>();
        FloorIndex(List<FloorSample> floors) {
            this.floors = List.copyOf(floors);
            for (FloorSample floor : floors) columns.computeIfAbsent(new Column(floor.floor().getX(),
                    floor.floor().getZ()), ignored -> new ArrayList<>()).add(floor);
        }
    }

    static List<Slot> reservePrimary(ServerLevel level, FloorSample anchor, FloorIndex plane,
            ItemStack item, int count, RandomGenerator random, List<AABB> occupied) {
        if (count <= 0 || plane.floors.isEmpty()) return List.of();
        AABB footprint = GroundLootFootprints.forItem(item, level);
        List<Slot> candidates = new ArrayList<>();
        for (FloorSample floor : plane.floors) {
            if (Math.abs(floor.floor().getX() - anchor.floor().getX()) > 1
                    || Math.abs(floor.floor().getZ() - anchor.floor().getZ()) > 1) continue;
            for (double x : OFFSETS) for (double z : OFFSETS) for (int rotation = 0; rotation < 4; rotation++) {
                Slot slot = candidate(level, plane, floor.floor(), floor.floor().getX() + x,
                        floor.floor().getZ() + z, footprint, randomYaw(random));
                if (slot != null) candidates.add(slot);
            }
        }
        shuffle(candidates, random);
        candidates.sort(Comparator.comparingDouble(slot -> distanceSquared(slot.x(), slot.z(),
                anchor.floor().getX() + 0.5, anchor.floor().getZ() + 0.5)));
        return reserveAvailable(candidates, count, occupied);
    }

    /** Every ammo pile stays on approved same-plane floor inside the configured annulus. */
    static List<Slot> reserveAmmo(ServerLevel level, Slot gun, FloorIndex plane, ItemStack ammo, int count,
            double minimumRadius, double maximumRadius, RandomGenerator random, List<AABB> occupied) {
        if (count <= 0 || plane.floors.isEmpty()) return List.of();
        AABB footprint = GroundLootFootprints.forItem(ammo, level);
        List<Slot> candidates = new ArrayList<>();
        for (FloorSample floor : plane.floors) {
            double centerDistance = Math.sqrt(distanceSquared(floor.floor().getX() + 0.5,
                    floor.floor().getZ() + 0.5, gun.x(), gun.z()));
            if (centerDistance > maximumRadius + 0.8 || centerDistance < minimumRadius - 0.8) continue;
            // A stable fallback plus random samples supports both thin rings and irregular floor shapes.
            for (int sample = 0; sample < 25; sample++) {
                double x = floor.floor().getX() + (sample < 9 ? OFFSETS[sample / 3] : 0.12 + random.nextDouble() * 0.76);
                double z = floor.floor().getZ() + (sample < 9 ? OFFSETS[sample % 3] : 0.12 + random.nextDouble() * 0.76);
                double distance = Math.sqrt(distanceSquared(x, z, gun.x(), gun.z()));
                if (distance + EPSILON < minimumRadius || distance - EPSILON > maximumRadius) continue;
                Slot slot = candidate(level, plane, floor.floor(), x, z, footprint, randomYaw(random));
                if (slot == null) continue;
                // Sweep the same actual ammo model bounds used for support and occupied space.
                // Keep the selected continuous yaw for both endpoints of the conservative sweep.
                AABB start = rotate(footprint, slot.yaw()).move(gun.x(), gun.y(), gun.z());
                if (!isClear(level, start.minmax(slot.bounds()))) continue;
                candidates.add(slot);
            }
        }
        shuffle(candidates, random);
        return reserveAvailable(candidates, count, occupied);
    }

    /** A crowded or empty area simply supplies fewer piles, without invalidating other loot. */
    static List<Slot> reserveAvailable(List<Slot> candidates, int count, List<AABB> occupied) {
        if (count <= 0) return List.of();
        List<Slot> result = new ArrayList<>();
        for (Slot slot : candidates) {
            if (occupied.stream().anyMatch(bounds -> bounds.intersects(slot.bounds()))) continue;
            result.add(slot);
            occupied.add(slot.bounds().inflate(0.02, 0, 0.02));
            if (result.size() == count) return List.copyOf(result);
        }
        return List.copyOf(result);
    }

    private static Slot candidate(ServerLevel level, FloorIndex plane, BlockPos floor,
            double x, double z, AABB local, float yaw) {
        double localX = x - floor.getX(), localZ = z - floor.getZ();
        for (Surface surface : SpawnFloorGeometry.topSurfaces(level, floor)) {
            if (localX < surface.minX() || localX > surface.maxX() || localZ < surface.minZ() || localZ > surface.maxZ()) continue;
            double y = floor.getY() + surface.y() + EPSILON;
            AABB bounds = rotate(local, yaw).move(x, y, z);
            if (!supported(level, plane, bounds, y) || !isClear(level, bounds)) continue;
            return new Slot(floor.immutable(), x, y, z, yaw, bounds);
        }
        return null;
    }

    /** Test every intersected approved tile, avoiding long guns hanging through an unmarked hole. */
    private static boolean supported(ServerLevel level, FloorIndex plane, AABB bounds, double y) {
        int minX = (int) Math.floor(bounds.minX + EPSILON), maxX = (int) Math.floor(bounds.maxX - EPSILON);
        int minZ = (int) Math.floor(bounds.minZ + EPSILON), maxZ = (int) Math.floor(bounds.maxZ - EPSILON);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            int tileX = x, tileZ = z;
            boolean supported = false;
            for (FloorSample sample : plane.columns.getOrDefault(new Column(tileX, tileZ), List.of())) {
                if (sample.floor().getX() != tileX || sample.floor().getZ() != tileZ) continue;
                for (Surface surface : SpawnFloorGeometry.topSurfaces(level, sample.floor())) {
                    if (Math.abs(sample.floor().getY() + surface.y() + EPSILON - y) > EPSILON) continue;
                    if (surface.minX() + tileX <= Math.max(bounds.minX, tileX) + EPSILON
                            && surface.maxX() + tileX >= Math.min(bounds.maxX, tileX + 1) - EPSILON
                            && surface.minZ() + tileZ <= Math.max(bounds.minZ, tileZ) + EPSILON
                            && surface.maxZ() + tileZ >= Math.min(bounds.maxZ, tileZ + 1) - EPSILON) supported = true;
                }
            }
            if (!supported) return false;
        }
        return true;
    }

    /** All spawned piles use independent continuous yaw from their saved region RNG. */
    static float randomYaw(RandomGenerator random) { return random.nextFloat() * 360F; }

    /** Full corner transform; the shared renderer footprint supports arbitrary Y rotation. */
    static AABB rotate(AABB box, float yaw) {
        return GroundLootFootprints.worldBounds(box, Vec3.ZERO, yaw);
    }
    private static boolean isClear(ServerLevel level, AABB bounds) {
        // Cross-gap swept boxes must never implicitly load an unmarked distant chunk.
        int minChunkX = ((int) Math.floor(bounds.minX)) >> 4;
        int maxChunkX = ((int) Math.floor(bounds.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(bounds.minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(bounds.maxZ)) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++)
            if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        for (var collision : level.getBlockCollisions(null, bounds)) if (!collision.isEmpty()) return false;
        return true;
    }
    private static double distanceSquared(double x, double z, double otherX, double otherZ) {
        double dx = x - otherX, dz = z - otherZ; return dx * dx + dz * dz;
    }
    private static <T> void shuffle(List<T> values, RandomGenerator random) {
        for (int i = values.size() - 1; i > 0; i--) {
            int other = random.nextInt(i + 1); T old = values.get(i); values.set(i, values.get(other)); values.set(other, old);
        }
    }
}
