package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneSavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** One-way map edges for ordinary movement; explicit placement and teleportation remain available. */
public final class MatchBoundaryService {
    private static final ResourceLocation TRANSPORT =
            ResourceLocation.fromNamespaceAndPath("tacz_pubg", "transport_formation");
    private record CachedMaps(long revision, List<MapDefinition> maps) {}
    private static final Map<MapZoneSavedData, CachedMaps> SERVER_MAPS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MatchBoundaryService() {}

    public static Vec3 constrain(Entity entity, Vec3 movement) {
        if (movement.x == 0 && movement.z == 0 || transport(entity)) return movement;
        List<MapDefinition> maps;
        if (entity.level() instanceof ServerLevel level) {
            MapZoneSavedData data = MapZoneSavedData.get(level.getServer());
            CachedMaps cached = SERVER_MAPS.get(data);
            if (cached == null || cached.revision() != data.revision()) {
                cached = new CachedMaps(data.revision(), data.maps());
                SERVER_MAPS.put(data, cached);
            }
            maps = cached.maps();
        } else if (entity.level().isClientSide) {
            maps = ClientMaps.current();
        } else return movement;

        return constrain(maps, entity.level().dimension().location(), entity.getX(), entity.getZ(), movement);
    }

    static Vec3 constrain(List<MapDefinition> maps, ResourceLocation dimension, double x, double z, Vec3 movement) {
        double dx = movement.x, dz = movement.z;
        for (MapDefinition map : maps) {
            // Membership is evaluated at the starting position, including the exact edge.
            // Entering a map from outside is allowed. Overlapping maps each constrain a member.
            if (!map.dimension().equals(dimension) || !map.contains(x, z)) continue;
            dx = clipAxis(x, dx, map.minX(), map.maxX());
            dz = clipAxis(z, dz, map.minZ(), map.maxZ());
        }
        return dx == movement.x && dz == movement.z ? movement : new Vec3(dx, movement.y, dz);
    }

    /** Clamp only the outward component so movement parallel to an edge and vertical movement remain smooth. */
    static double clipAxis(double position, double movement, double minimum, double maximum) {
        return Math.clamp(movement, minimum - position, maximum - position);
    }

    /** Applied after vanilla's invalid-packet check, before its final absolute-position assignment. */
    public static double constrainTargetX(Entity entity, double targetX) {
        Vec3 requested = new Vec3(targetX - entity.getX(), 0, 0);
        Vec3 constrained = constrain(entity, requested);
        return requested == constrained ? targetX : entity.getX() + constrained.x;
    }

    public static double constrainTargetZ(Entity entity, double targetZ) {
        Vec3 requested = new Vec3(0, 0, targetZ - entity.getZ());
        Vec3 constrained = constrain(entity, requested);
        return requested == constrained ? targetZ : entity.getZ() + constrained.z;
    }

    private static boolean transport(Entity entity) {
        return TRANSPORT.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getRootVehicle().getType()));
    }

    /** Kept in a lazily loaded class so dedicated servers never resolve client-only map state. */
    private static final class ClientMaps {
        private static List<MapDefinition> current() {
            return com.example.examplemod.zones.client.MapOverlayClient.allMaps();
        }
    }
}
