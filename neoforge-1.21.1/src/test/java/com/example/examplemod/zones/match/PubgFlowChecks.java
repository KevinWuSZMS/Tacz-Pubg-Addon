package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchConfigModel.FlowRules;
import com.example.examplemod.zones.match.MatchConfigModel.Phase;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Bounded integration fixtures for match-clock mechanics; no server, world or graphics context starts. */
public final class PubgFlowChecks {
    private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
    private static final MatchEngine.Participant PLAYER = new MatchEngine.Participant(new UUID(0, 1), "Player", "one");
    private static int checks;

    public static void main(String[] args) {
        openingAndSharedClock();
        flightGeometry();
        exposureAndReentry();
        independentBombing();
        System.out.println("PubgFlowChecks: " + checks + " focused match-flow checks passed; no game launched.");
    }

    private static void openingAndSharedClock() {
        var flow = new FlowRules(5, 20, 6, 2, 3, 4, 2, .35, 2, 4, 3);
        var game = engine(map(null), settings(true, flow, List.of(new Phase(3, 4, .5, 1, false),
                new Phase(2, 2, .5, 2, false))), 17);
        check(game.status().stage().equals("no_circle") && game.status().secondsRemaining() == 5
                && game.status().phase() == 0, "opening delay is a separate battle stage");
        check(game.status().currentCircle() == null && game.status().targetCircle() == null
                && game.projectedMap().safeZone() == null && game.bombingZones().isEmpty(),
                "opening exposes neither blue, white nor red circles");
        check(game.damageAt(PLAYER.id(), WORLD, 95, 95, true) == 0
                && !game.blocksPlayerDamage(PLAYER.id(), PLAYER.id()),
                "opening allows combat and looting without blue damage");
        tick(game, 99);
        check(game.status().stage().equals("no_circle") && game.status().secondsRemaining() == 1,
                "white circle is not announced before the exact delay boundary");
        tick(game, 1);
        Circle target = game.status().targetCircle();
        check(game.status().stage().equals("waiting") && target != null
                && game.status().currentCircle() == null && game.status().secondsRemaining() == 3,
                "white circle appears at the exact delay boundary while blue remains absent");
        check(MatchCirclePreview.target(game.status(), 1).equals(target),
                "map overlay announces the first white circle without a blue boundary");
        tick(game, 19);
        check(game.bombingZones().isEmpty() && !game.bombingActive(), "red zone does not appear before its independent delay");
        tick(game, 1);
        check(game.status().bombingStage().equals("warning") && !game.bombingZones().isEmpty() && !game.bombingActive(),
                "red warning displays its circle before any strikes");
        var red = game.bombingZones();
        var before = game.status();
        long ticks = game.elapsedTicks();
        game.pause(); tick(game, 200);
        check(game.elapsedTicks() == ticks && game.status().secondsRemaining() == before.secondsRemaining()
                && game.flightProgress() == before.flightProgress()
                && game.status().bombingSeconds() == before.bombingSeconds()
                && game.bombingZones().equals(red) && !game.bombingActive(),
                "pause freezes all three clocks and suppresses strikes");
        game.resume(); tick(game, 40);
        check(game.status().stage().equals("shrinking") && game.bombingActive() && game.bombingZones().equals(red),
                "first contraction and red strikes begin on their exact resumed boundaries");
        near(game.status().currentCircle().radius(), Math.hypot(100, 100),
                "first blue boundary initially covers the entire playable rectangle");
        check(game.damageAt(PLAYER.id(), WORLD, 95, 95, true) == 0,
                "first contraction does not instantly damage players at map corners");
        tick(game, 40);
        near(game.status().currentCircle().radius(), (Math.hypot(100, 100) + target.radius()) / 2,
                "first boundary closes continuously toward the fixed white circle");
        check(game.damageAt(PLAYER.id(), WORLD, 95, 95, true) == 1, "damage begins only after the blue wall passes the player");
        tick(game, 40);
        check(game.status().phase() == 2 && game.status().stage().equals("waiting")
                && game.status().currentCircle().equals(target), "completed first contraction becomes the next phase's blue boundary");
        check(!game.bombingActive() && game.bombingZones().isEmpty(), "red circle disappears during cooldown");
        game.stop();
        check(game.flightProgress() < 0 && game.bombingZones().isEmpty() && !game.bombingActive(),
                "ending the match clears its flight and bombing presentation");
    }

    private static void flightGeometry() {
        MapDefinition map = map(null);
        Circle first = new Circle(17, -25, 30);
        for (int seed = 0; seed < 12; seed++) {
            FlightPath route = MatchFlightPath.generate(map, first, seed);
            check(route.equals(MatchFlightPath.generate(map, first, seed)), "route is deterministic for the match seed");
            check(onBoundary(map, route.startX(), route.startZ()) && onBoundary(map, route.endX(), route.endZ()),
                    "generated flight enters and exits at map boundaries");
            double dx = route.endX() - route.startX(), dz = route.endZ() - route.startZ();
            double distance = Math.abs(dz * (first.centerX() - route.startX())
                    - dx * (first.centerZ() - route.startZ())) / Math.hypot(dx, dz);
            check(distance <= first.radius() + 1e-8, "generated route crosses the first safe circle");
        }
        FlightPath authored = new FlightPath(-100, -30, 100, 20);
        check(MatchFlightPath.generate(map(authored), first, 17).equals(authored), "authored route is never replaced");
        var flow = new FlowRules(5, 2, 150, 10, 20, 60, 2, .35, 2, 4, 3);
        var game = engine(map, settings(true, flow, List.of(new Phase(1, 1, .5, 1, false))), 17);
        int leadInTicks = game.settings().flightRules().jumpDelaySeconds() * 20;
        check(leadInTicks > 0 && game.flightRouteProgress() < 0 && game.flightProgress() < 0,
                "battle starts with the aircraft before the route boundary during jump-lock lead-in");
        tick(game, leadInTicks - 1);
        check(game.flightRouteProgress() < 0 && game.flightProgress() < 0,
                "aircraft remains outside the route until the exact jump-lock boundary");
        tick(game, 1);
        near(game.flightRouteProgress(), 0, "aircraft reaches the route start exactly when jump lock ends");
        near(game.flightProgress(), 0, "map flight marker begins at the route boundary");
        tick(game, (int) game.flightDurationTicks() - leadInTicks - 1);
        check(game.flightProgress() >= 0 && game.flightProgress() < 1, "flight remains visible before its configured endpoint");
        tick(game, 1);
        check(game.flightProgress() < 0, "flight marker ends at its exact duration");
    }

    private static void exposureAndReentry() {
        var flow = new FlowRules(0, 0, 150, 10, 20, 60, 2, .35, 2, 4, 3);
        var game = engine(map(null), settings(true, flow, List.of(new Phase(0, 1, .5, 3, false))), 17);
        presence(game, 0, 0); tick(game, 20);
        presence(game, 20, 0); tick(game, 40);
        near(game.damageAt(PLAYER.id(), WORLD, 20, 0, true), 3, "exposure grace retains the configured base damage");
        tick(game, 40);
        near(game.damageAt(PLAYER.id(), WORLD, 20, 0, true), 6, "continued exposure ramps independently of phase base damage");
        tick(game, 40);
        near(game.damageAt(PLAYER.id(), WORLD, 20, 0, true), 9, "exposure multiplier is capped");
        game.pause(); tick(game, 40);
        check(game.damageAt(PLAYER.id(), WORLD, 20, 0, true) == 0, "paused match suppresses accumulated exposure damage");
        game.resume();
        near(game.damageAt(PLAYER.id(), WORLD, 20, 0, true), 9, "pause does not advance or reset the exposure clock");
        presence(game, 0, 0); tick(game, 1);
        presence(game, 20, 0); tick(game, 1);
        near(game.damageAt(PLAYER.id(), WORLD, 20, 0, true), 3, "returning to safety resets sustained exposure");
        near(MatchEngine.exposureMultiplier(41, 2, 0, 3), 3, "zero ramp switches to the cap only after the grace boundary");
    }

    private static void independentBombing() {
        var flow = new FlowRules(0, 0, 1, 1, 2, 2, 2, .35, 2, 4, 3);
        var game = engine(map(null), settings(false, flow, List.of(new Phase(10, 10, .5, 3, false))), 17);
        tick(game, 20);
        check(game.status().currentCircle() == null && !game.bombingZones().isEmpty() && !game.bombingActive(),
                "disabling blue still allows the independently enabled red warning");
        tick(game, 20);
        check(game.bombingActive() && game.damageAt(PLAYER.id(), WORLD, 95, 95, true) == 0,
                "disabling blue does not disable red strikes or re-enable blue damage");
        var base = settings(true, flow, List.of(new Phase(10, 10, .5, 3, false)));
        var disabled = new Settings(base.mapId(), base.name(), base.minPlayers(), base.maxPlayers(), base.teamSize(),
                base.preparationSeconds(), base.maxMatchSeconds(), base.disconnectGraceSeconds(), base.blueEnabled(),
                base.autoEnd(), base.spectateEliminated(), base.friendlyFire(), base.phases(),
                new MatchConfigModel.BombingRules(false, .12, 60, base.bombingRules().projectile()), flow);
        game = engine(map(null), disabled, 17); tick(game, 40);
        check(game.bombingZones().isEmpty() && !game.bombingActive(), "explicit bombing-off setting remains authoritative");
    }

    private static Settings settings(boolean blue, FlowRules flow, List<Phase> phases) {
        return new Settings(1, "flow", 1, 20, 1, 0, 60, 60, blue, false, true, false,
                phases, MatchConfigModel.BombingRules.defaults(), flow);
    }
    private static MatchEngine engine(MapDefinition map, Settings settings, long seed) {
        return new MatchEngine(new MatchSession(new UUID(0, seed), 0, map), settings, List.of(PLAYER), seed);
    }
    private static MapDefinition map(FlightPath flight) {
        return new MapDefinition(1, "map", WORLD, -100, -100, 100, 100,
                new Circle(0, 0, 10), 1, List.of(), flight);
    }
    private static boolean onBoundary(MapDefinition map, double x, double z) {
        return map.contains(x, z) && (Math.abs(x - map.minX()) < 1e-8 || Math.abs(x - map.maxX()) < 1e-8
                || Math.abs(z - map.minZ()) < 1e-8 || Math.abs(z - map.maxZ()) < 1e-8);
    }
    private static void presence(MatchEngine game, double x, double z) {
        game.presence(PLAYER.id(), new MatchEngine.Presence(true, true, true, WORLD, x, z));
    }
    private static void tick(MatchEngine game, int count) { for (int i = 0; i < count; i++) game.advance(); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
