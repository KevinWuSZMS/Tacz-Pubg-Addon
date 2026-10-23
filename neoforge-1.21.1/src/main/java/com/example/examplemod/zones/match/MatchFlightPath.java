package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import java.util.Random;

/** Seeded automatic route crossing the first safe circle. Authored routes take precedence. */
public final class MatchFlightPath {
    private MatchFlightPath() {}
    public static FlightPath generate(MapDefinition map, Circle first, long seed) {
        return generate(map, first, seed, map.flightPath() == null);
    }
    public static FlightPath generate(MapDefinition map, Circle first, long seed, boolean randomRoute) {
        if (!randomRoute) {
            if (map.flightPath() == null) throw new IllegalArgumentException(
                    com.example.examplemod.localization.Texts.tr("message.tacz_pubg.flight.no_route"));
            return map.flightPath();
        }
        Random random = new Random(seed ^ 0x504C414E45L);
        double angle = random.nextDouble() * Math.PI * 2, dx = Math.cos(angle), dz = Math.sin(angle);
        double x = first == null ? (map.minX() + map.maxX()) / 2 : first.centerX();
        double z = first == null ? (map.minZ() + map.maxZ()) / 2 : first.centerZ();
        if (first != null) {
            double offset = (random.nextDouble() * 2 - 1) * first.radius() * .65;
            x -= dz * offset; z += dx * offset;
        }
        double marginX = Math.min(.01, (map.maxX() - map.minX()) * .001);
        double marginZ = Math.min(.01, (map.maxZ() - map.minZ()) * .001);
        x = Math.clamp(x, map.minX() + marginX, map.maxX() - marginX);
        z = Math.clamp(z, map.minZ() + marginZ, map.maxZ() - marginZ);
        double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
        if (Math.abs(dx) > 1e-9) {
            double a = (map.minX() - x) / dx, b = (map.maxX() - x) / dx;
            low = Math.max(low, Math.min(a, b)); high = Math.min(high, Math.max(a, b));
        }
        if (Math.abs(dz) > 1e-9) {
            double a = (map.minZ() - z) / dz, b = (map.maxZ() - z) / dz;
            low = Math.max(low, Math.min(a, b)); high = Math.min(high, Math.max(a, b));
        }
        return new FlightPath(Math.clamp(x + dx * low, map.minX(), map.maxX()),
                Math.clamp(z + dz * low, map.minZ(), map.maxZ()),
                Math.clamp(x + dx * high, map.minX(), map.maxX()),
                Math.clamp(z + dz * high, map.minZ(), map.maxZ()));
    }
}
