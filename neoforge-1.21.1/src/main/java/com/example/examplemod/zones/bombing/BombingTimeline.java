package com.example.examplemod.zones.bombing;

import com.example.examplemod.zones.MapZoneModel.BombingZone;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchConfigModel;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Match-clock red-zone windows. The configurable seconds are Minecraft adaptation values,
 * not unpublished PUBG constants. Geometry is chosen once per warning and held through its strike.
 */
public final class BombingTimeline {
    public record Frame(List<BombingZone> zones, boolean striking, String stage, int secondsRemaining) {
        public Frame { zones = List.copyOf(zones); }
    }
    private static final Frame OFF = new Frame(List.of(), false, "off", 0);
    private static final int CENTER_ATTEMPTS = 48;
    private final MapDefinition map;
    private final MatchConfigModel.Settings settings;
    private final long seed;
    private long selectedCycle = -1;
    private List<BombingZone> selected = List.of();

    public BombingTimeline(MapDefinition map, MatchConfigModel.Settings settings, long seed) {
        this.map = Objects.requireNonNull(map);
        this.settings = Objects.requireNonNull(settings);
        this.seed = seed;
    }

    public Frame frame(long combatTicks, int phase, Circle currentSafeZone) {
        var rules = settings.bombingRules();
        var flow = settings.flowRules();
        if (rules != null && !rules.enabled() || phase <= 0 || phase > flow.bombingLastPhase()
                || currentSafeZone == null) return OFF;
        long first = flow.bombingFirstSeconds() * 20L;
        if (combatTicks < first) return new Frame(List.of(), false, "pending", seconds(first - combatTicks));
        long warning = flow.bombingWarningSeconds() * 20L;
        long strike = flow.bombingDurationSeconds() * 20L;
        long period = warning + strike + flow.bombingCooldownSeconds() * 20L;
        if (strike <= 0 || period <= 0) return OFF;
        long cycle = (combatTicks - first) / period;
        long within = (combatTicks - first) % period;
        if (within >= warning + strike)
            return new Frame(List.of(), false, "cooldown", seconds(period - within));
        if (selectedCycle != cycle) {
            selectedCycle = cycle;
            selected = choose(cycle, currentSafeZone);
        }
        if (selected.isEmpty())
            return new Frame(List.of(), false, "quiet", 0);
        return within < warning
                ? new Frame(selected, false, "warning", seconds(warning - within))
                : new Frame(selected, true, "striking", seconds(warning + strike - within));
    }

    private List<BombingZone> choose(long cycle, Circle currentSafeZone) {
        Random random = new Random(seed ^ (cycle + 1) * 0x9E3779B97F4A7C15L);
        long id = cycle + 1;
        if (!map.bombingZones().isEmpty()) {
            // Author-selected areas remain author-selected; each wave activates only one candidate.
            List<BombingZone> candidates = map.bombingZones().stream()
                    .filter(zone -> map.contains(zone.area().centerX(), zone.area().centerZ())
                            && currentSafeZone.contains(zone.area().centerX(), zone.area().centerZ()))
                    .toList();
            if (candidates.isEmpty()) return List.of();
            BombingZone template = candidates.get(random.nextInt(candidates.size()));
            return List.of(new BombingZone(id, template.area(), template.missilesPerMinute(),
                    template.missileDamage(), template.blastRadius()));
        }
        var rules = settings.bombingRules() == null
                ? MatchConfigModel.BombingRules.defaults() : settings.bombingRules();
        double half = Math.min(map.maxX() - map.minX(), map.maxZ() - map.minZ()) / 2;
        double radius = Math.min(half, Math.max(.5, half * rules.radiusRatio()));
        if (!(radius > 0)) return List.of();
        double minX = map.minX() + radius, maxX = map.maxX() - radius;
        double minZ = map.minZ() + radius, maxZ = map.maxZ() - radius;
        for (int attempt = 0; attempt < CENTER_ATTEMPTS; attempt++) {
            double x = minX + random.nextDouble() * Math.max(0, maxX - minX);
            double z = minZ + random.nextDouble() * Math.max(0, maxZ - minZ);
            if (currentSafeZone.contains(x, z))
                return generated(id, new Circle(x, z, radius), rules);
        }
        // Deterministic bounded fallback, without loading chunks or biasing toward live players.
        double x = Math.clamp(currentSafeZone.centerX(), minX, maxX);
        double z = Math.clamp(currentSafeZone.centerZ(), minZ, maxZ);
        return currentSafeZone.contains(x, z)
                ? generated(id, new Circle(x, z, radius), rules) : List.of();
    }

    private static List<BombingZone> generated(long id, Circle area, MatchConfigModel.BombingRules rules) {
        return List.of(new BombingZone(id, area, (int) rules.roundsPerMinute(),
                rules.projectile().damage(), rules.projectile().damageRadius()));
    }

    private static int seconds(long ticks) {
        return (int) Math.min(Integer.MAX_VALUE, (Math.max(0, ticks) + 19) / 20);
    }
}
