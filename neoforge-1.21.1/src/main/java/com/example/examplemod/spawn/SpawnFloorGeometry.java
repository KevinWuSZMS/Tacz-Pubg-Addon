package com.example.examplemod.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/** Shared server validation and client geometry for real, upward-facing floor surfaces. */
public final class SpawnFloorGeometry {
    private static final double EPSILON = 1.0e-6;
    public static final double MIN_CLEARANCE = 0.25;

    private SpawnFloorGeometry() {}

    public record Surface(double minX, double minZ, double maxX, double maxZ, double y) {}

    public static List<Surface> topSurfaces(BlockGetter level, BlockPos pos) {
        return topSurfaces(level.getBlockState(pos).getCollisionShape(level, pos).toAabbs());
    }

    /** Split X/Z into cells and retain only the upper envelope, including stair treads. */
    static List<Surface> topSurfaces(List<AABB> boxes) {
        if (boxes.isEmpty()) return List.of();
        TreeSet<Double> xs = new TreeSet<>();
        TreeSet<Double> zs = new TreeSet<>();
        xs.add(0.0);
        xs.add(1.0);
        zs.add(0.0);
        zs.add(1.0);
        for (AABB box : boxes) {
            // Protruding fences/walls and similar geometry are not authorable floor cells.
            if (box.minX < -EPSILON || box.minZ < -EPSILON || box.minY < -EPSILON
                    || box.maxX > 1 + EPSILON || box.maxZ > 1 + EPSILON || box.maxY > 1 + EPSILON) {
                return List.of();
            }
            xs.add(Math.clamp(box.minX, 0, 1));
            xs.add(Math.clamp(box.maxX, 0, 1));
            zs.add(Math.clamp(box.minZ, 0, 1));
            zs.add(Math.clamp(box.maxZ, 0, 1));
        }
        List<Double> xEdges = new ArrayList<>(xs);
        List<Double> zEdges = new ArrayList<>(zs);
        List<Surface> result = new ArrayList<>();
        for (int x = 0; x + 1 < xEdges.size(); x++) {
            double minX = xEdges.get(x), maxX = xEdges.get(x + 1);
            if (maxX - minX <= EPSILON) continue;
            for (int z = 0; z + 1 < zEdges.size(); z++) {
                double minZ = zEdges.get(z), maxZ = zEdges.get(z + 1);
                if (maxZ - minZ <= EPSILON) continue;
                double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
                double top = 0;
                for (AABB box : boxes) {
                    if (cx > box.minX - EPSILON && cx < box.maxX + EPSILON
                            && cz > box.minZ - EPSILON && cz < box.maxZ + EPSILON) {
                        top = Math.max(top, box.maxY);
                    }
                }
                if (top > EPSILON) result.add(new Surface(minX, minZ, maxX, maxZ, top));
            }
        }
        return List.copyOf(result);
    }

    public static boolean isValidFloor(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || level.isOutsideBuildHeight(pos.above())
                || !level.getWorldBorder().isWithinBounds(pos)
                || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) return false;
        List<Surface> surfaces = topSurfaces(level, pos);
        double area = surfaces.stream().mapToDouble(s -> (s.maxX - s.minX) * (s.maxZ - s.minZ)).sum();
        if (Math.abs(area - 1) > EPSILON) return false;
        List<AABB> above = level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).toAabbs();
        for (Surface surface : surfaces) {
            AABB clearance = new AABB(surface.minX + EPSILON, surface.y + EPSILON,
                    surface.minZ + EPSILON, surface.maxX - EPSILON,
                    surface.y + MIN_CLEARANCE, surface.maxZ - EPSILON);
            for (AABB obstruction : above) {
                if (clearance.intersects(obstruction.move(0, 1, 0))) return false;
            }
        }
        return true;
    }
}
