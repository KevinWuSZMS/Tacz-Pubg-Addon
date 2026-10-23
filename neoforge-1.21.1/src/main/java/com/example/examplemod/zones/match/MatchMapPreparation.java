package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.BombingZone;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import java.util.List;
import java.util.Random;

/** Runtime geometry derived from saved boundaries; authored map data remains untouched. */
public final class MatchMapPreparation {
    private MatchMapPreparation() {}

    public static MapDefinition prepare(MapDefinition map, MatchConfigModel.Settings settings, long seed) {
        double half = Math.min(map.maxX() - map.minX(), map.maxZ() - map.minZ()) / 2;
        Circle safe = map.safeZone() == null ? new Circle((map.minX() + map.maxX()) / 2,
                (map.minZ() + map.maxZ()) / 2, Math.max(.5, half)) : map.safeZone();
        var rules = settings.bombingRules();
        List<BombingZone> bombs = map.bombingZones();
        if (rules != null && !rules.enabled()) bombs = List.of();
        else if (rules != null) {
            bombs = bombs.stream().map(b -> new BombingZone(b.id(), b.area(), (int) rules.roundsPerMinute(),
                    rules.projectile().damage(), rules.projectile().damageRadius())).toList();
        }
        return new MapDefinition(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                safe, map.blueDamagePerSecond(), bombs, map.flightPath(), map.visualStyle(), map.stagingSpawn());
    }
}
