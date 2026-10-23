package com.example.examplemod.zones.bombing;

import com.example.examplemod.zones.MapZoneModel.BombingZone;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchConfigModel;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** Necessary boundary checks for red-zone timing; no world, TaCZ, or client startup. */
public final class BombingTimelineChecks {
    private static int checks;
    private static final Circle SAFE = new Circle(0, 0, 100);
    private BombingTimelineChecks() {}

    public static void main(String[] args) {
        boundaries();
        seededGeometry();
        authoredCandidates();
        disabled();
        System.out.println("BombingTimelineChecks: " + checks + " assertions passed");
    }

    private static void boundaries() {
        var schedule = timeline(map(List.of()), 51);
        check(schedule.frame(0, 1, SAFE).zones().isEmpty(), "No red zone at the start");
        check(schedule.frame(2999, 1, SAFE).zones().isEmpty(), "First appearance waits the full delay");
        var warning = schedule.frame(3000, 1, SAFE);
        check(warning.stage().equals("warning") && !warning.striking() && warning.secondsRemaining() == 10,
                "Warning starts at first delay and cannot launch missiles");
        check(warning.zones().size() == 1, "One visible warning circle");
        var finalWarning = schedule.frame(3199, 1, SAFE);
        check(!finalWarning.striking() && finalWarning.secondsRemaining() == 1, "Last warning tick remains harmless");
        var strike = schedule.frame(3200, 1, SAFE);
        check(strike.striking() && strike.secondsRemaining() == 20, "Strikes begin only after warning");
        check(strike.zones().equals(warning.zones()), "Announced area stays fixed for strikes");
        check(schedule.frame(3599, 2, SAFE).striking(), "Last strike tick remains active");
        var cooldown = schedule.frame(3600, 2, SAFE);
        check(!cooldown.striking() && cooldown.zones().isEmpty() && cooldown.secondsRemaining() == 60,
                "Ending the strike clears the red circle");
        check(schedule.frame(4799, 2, SAFE).zones().isEmpty(), "No early next-wave warning");
        var next = schedule.frame(4800, 2, SAFE);
        check(next.stage().equals("warning") && next.zones().getFirst().id() != warning.zones().getFirst().id(),
                "A fresh warning and rate identity start the next wave");
        check(schedule.frame(4800, 2, SAFE).equals(next), "A frozen match clock cannot advance the warning");
        check(schedule.frame(4801, 3, SAFE).zones().isEmpty(), "Phase three immediately disables all red zones");
        check(schedule.frame(3000, 0, SAFE).zones().isEmpty(), "No red zone in a pre-circle phase");
    }

    private static void seededGeometry() {
        MapDefinition map = map(List.of());
        Circle safe = new Circle(20, -10, 50);
        for (int seed = 0; seed < 8; seed++) {
            var first = timeline(map, seed).frame(3000, 1, safe);
            var repeated = timeline(map, seed).frame(3000, 1, safe);
            check(first.equals(repeated), "Same match seed creates the same wave");
            Circle area = first.zones().getFirst().area();
            check(safe.contains(area.centerX(), area.centerZ()), "Generated center lies in current safe circle");
            check(area.centerX() - area.radius() >= map.minX() && area.centerX() + area.radius() <= map.maxX()
                            && area.centerZ() - area.radius() >= map.minZ() && area.centerZ() + area.radius() <= map.maxZ(),
                    "Generated bombing circle remains inside the map");
        }
        check(timeline(map, 3).frame(3000, 1, new Circle(1000, 1000, 1)).zones().isEmpty(),
                "A disjoint safe area yields no out-of-map bombardment");
    }

    private static void authoredCandidates() {
        var allowed = new BombingZone(7, new Circle(10, 10, 12), 30, 8, 3);
        var outside = new BombingZone(8, new Circle(-80, -80, 5), 120, 20, 6);
        var schedule = timeline(map(List.of(allowed, outside)), 8);
        var frame = schedule.frame(3000, 1, new Circle(10, 10, 20));
        check(frame.zones().size() == 1, "Only one eligible authored candidate is activated");
        var zone = frame.zones().getFirst();
        check(zone.area().equals(allowed.area()) && zone.missilesPerMinute() == allowed.missilesPerMinute()
                        && zone.missileDamage() == allowed.missileDamage() && zone.blastRadius() == allowed.blastRadius(),
                "Authored geometry, rate and blast settings survive");
        check(timeline(map(List.of(outside)), 8).frame(3000, 1, new Circle(10, 10, 20)).zones().isEmpty(),
                "Authored regions are not silently moved elsewhere");
    }

    private static void disabled() {
        var base = MatchConfigModel.defaults(1);
        var rules = base.bombingRules();
        var disabled = new MatchConfigModel.Settings(base.mapId(), base.name(), base.minPlayers(), base.maxPlayers(),
                base.teamSize(), base.preparationSeconds(), base.maxMatchSeconds(), base.disconnectGraceSeconds(),
                base.blueEnabled(), base.autoEnd(), base.spectateEliminated(), base.friendlyFire(), base.phases(),
                new MatchConfigModel.BombingRules(false, rules.radiusRatio(), rules.roundsPerMinute(), rules.projectile()),
                base.flowRules());
        check(new BombingTimeline(map(List.of()), disabled, 1).frame(3200, 1, SAFE).zones().isEmpty(),
                "Disabled bombardment never shows a warning or strike area");
    }

    private static BombingTimeline timeline(MapDefinition map, long seed) {
        return new BombingTimeline(map, MatchConfigModel.defaults(1), seed);
    }
    private static MapDefinition map(List<BombingZone> zones) {
        return new MapDefinition(1, "Timeline fixture", ResourceLocation.parse("minecraft:overworld"),
                -100, -100, 100, 100, SAFE, 1, zones);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
