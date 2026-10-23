package com.example.examplemod.zones.match.bot;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;

/** World reads are bounded to loaded chunks. Navigation leaves block interaction to administrators. */
final class BotTerrain {
    private BotTerrain() {}
    static boolean loaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }
    static boolean standing(ServerLevel level, MapDefinition map, int x, int y, int z) {
        if (!map.contains(x + .5, z + .5) || y <= level.getMinBuildHeight() || y + 2 >= level.getMaxBuildHeight()) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (!loaded(level, x + dx, z + dz)) return false;
        BlockPos feet = new BlockPos(x, y, z), head = feet.above(), floor = feet.below();
        BlockState a = level.getBlockState(feet), b = level.getBlockState(head), c = level.getBlockState(floor);
        if (!a.getFluidState().isEmpty() || !b.getFluidState().isEmpty() || !c.getFluidState().isEmpty()
                || hazard(a) || hazard(b) || hazard(c)) return false;
        var support = c.getCollisionShape(level, floor);
        return a.getCollisionShape(level, feet).isEmpty() && b.getCollisionShape(level, head).isEmpty()
                && !support.isEmpty() && support.bounds().maxY >= .9;
    }
    private static boolean hazard(BlockState state) {
        return state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW);
    }
    static BotPathfinder.Cell step(ServerLevel level, MapDefinition map, BotPathfinder.Cell from, int x, int z) {
        for (int offset : new int[] {0, 1, -1, -2})
            if (standing(level, map, x, from.y() + offset, z)) return new BotPathfinder.Cell(x, from.y() + offset, z);
        return null;
    }
    static BlockPos surface(ServerLevel level, MapDefinition map, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return null;
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        return standing(level, map, x, y, z) ? new BlockPos(x, y, z) : null;
    }
    static boolean loadedSegment(ServerLevel level, Vec3 from, Vec3 to) {
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(to.x - from.x), Math.abs(to.z - from.z)) / 4));
        if (steps > 40) return false;
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.lerp(to, i / (double) steps);
            int x = (int) Math.floor(point.x), z = (int) Math.floor(point.z);
            // A four-block sample checks the surrounding chunks too, covering short corner crossings.
            for (int dx : new int[] {-4, 4}) for (int dz : new int[] {-4, 4})
                if (!loaded(level, x + dx, z + dz)) return false;
        }
        return true;
    }
    static boolean visible(Entity observer, Vec3 point, BotRules.Budget budget) {
        ServerLevel level = (ServerLevel) observer.level(); Vec3 eye = observer.getEyePosition();
        if (!loadedSegment(level, eye, point) || !budget.ray()) return false;
        if (com.example.examplemod.throwables.ThrowableService.blinded(observer)
                || com.example.examplemod.throwables.ThrowableService.blocksSight(level, eye, point)) return false;
        return level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer))
                .getType() == HitResult.Type.MISS;
    }
}
