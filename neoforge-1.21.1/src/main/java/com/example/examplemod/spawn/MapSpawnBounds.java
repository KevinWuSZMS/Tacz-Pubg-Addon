package com.example.examplemod.spawn;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.Collection;

/** A floor's entire block footprint must fit in one map, including fractional map borders. */
public final class MapSpawnBounds {
    private MapSpawnBounds() {}
    public static boolean contains(MapDefinition map, ResourceLocation dimension, BlockPos pos) {
        return map.dimension().equals(dimension) && pos != null
                && pos.getX() >= map.minX() && pos.getX() + 1 <= map.maxX()
                && pos.getZ() >= map.minZ() && pos.getZ() + 1 <= map.maxZ();
    }
    public static boolean containedByOneMap(Collection<MapDefinition> maps, ResourceLocation dimension, BlockPos a, BlockPos b) {
        return a != null && b != null && maps.stream().anyMatch(m -> contains(m, dimension, a) && contains(m, dimension, b));
    }
}
