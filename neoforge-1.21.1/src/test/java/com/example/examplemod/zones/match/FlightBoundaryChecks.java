package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.FlightPath;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchConfigModel.FlightRules;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import com.example.examplemod.zones.match.flight.FlightGeometry;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Focused rule persistence, formation, clock and one-way-edge checks; no world or client is started. */
public final class FlightBoundaryChecks {
    private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
    private static int checks;
    public static void main(String[] args) {
        var authored = new FlightPath(0, 50, 100, 50);
        var map = map(1, 0, 100, authored);
        var defaults = MatchConfigModel.defaults(1);
        check(!defaults.allowBlockDrops() && !defaults.allowMobDrops() && defaults.flightRules().randomRoute(),
                "safe defaults for legacy and new rules");
        check(defaults.flightRules().heightAboveGround() == 512, "default flight height is 512 blocks");
        check(new FlightRules(true, true, 40, 2000, 3, 200).heightAboveGround() == 2000, "2000-block flight height is accepted");
        check(new FlightRules(true, true, 40, 512, 3, 52).autoDeployHeight() == 52, "Ten-percent minimum rounds upward for integer block heights");
        check(new FlightRules(true, true, 40, 512, 3, 512).autoDeployHeight() == 512, "Deployment can equal the flight height");
        for (int invalid : new int[]{51, 513}) {
            boolean invalidRejected = false;
            try { new FlightRules(true, true, 40, 512, 3, invalid); }
            catch (IllegalArgumentException expected) { invalidRejected = true; }
            check(invalidRejected, "Deployment is bounded by ten to one hundred percent: " + invalid);
        }
        var oldFlight = JsonParser.parseString(MatchConfigModel.toJson(defaults)).getAsJsonObject();
        oldFlight.getAsJsonObject("flightRules").addProperty("autoDeployHeight", 40);
        check(MatchConfigModel.parse(oldFlight.toString()).flightRules().autoDeployHeight() == 52,
                "Previously valid saved deployment heights migrate to the new minimum without losing rules");
        var json = JsonParser.parseString(MatchConfigModel.toJson(defaults)).getAsJsonObject();
        json.remove("flightRules"); json.remove("allowBlockDrops"); json.remove("allowMobDrops");
        var legacy = MatchConfigModel.parse(json.toString());
        check(!legacy.allowBlockDrops() && !legacy.allowMobDrops() && legacy.flightRules().equals(FlightRules.defaults()),
                "legacy rule fields receive defaults");
        var manual = settings(new FlightRules(true, false, 10, 128, 3, 40));
        check(MatchConfigModel.parse(MatchConfigModel.toJson(manual)).equals(manual), "flight fields round trip");
        check(MatchFlightPath.generate(map, map.safeZone(), 20, false).equals(authored), "manual flight remains unchanged");
        var random = MatchFlightPath.generate(map, map.safeZone(), 20, true);
        check(!random.equals(authored), "random mode replaces authored route");
        check(!random.equals(MatchFlightPath.generate(map, map.safeZone(), 21, true)), "new round seed changes route");
        check(map.contains(random.startX(), random.startZ()) && map.contains(random.endX(), random.endZ()), "route remains bounded");
        boolean rejected = false;
        try { MatchFlightPath.generate(map(2, 0, 100, null), map.safeZone(), 1, false); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "manual flight requires an authored route");

        var occupied = new HashSet<Vec3>();
        for (int slot = 0; slot < 12; slot++) {
            Vec3 offset = FlightGeometry.offset(slot, 0, 1);
            check(occupied.add(offset) && offset.x != 0 && Math.abs(offset.x) <= 2
                    && offset.z == -(slot / 4), "AA aisle AA stable seat " + slot);
        }
        check(FlightGeometry.offset(4, 1, 0).equals(new Vec3(-1, 0, 2)), "formation rotates with aircraft");
        var player = new MatchEngine.Participant(UUID.randomUUID(), "One", "One");
        var game = new MatchEngine(new MatchSession(UUID.randomUUID(), 0, map), manual, List.of(player), 3);
        check(game.flightDurationTicks() == 260, "duration includes jump-lock lead-in and route length / speed");
        check(Math.abs(game.flightRouteProgress() + .3) < 1e-9 && game.flightProgress() < 0,
                "aircraft begins 30 blocks before the route and is hidden from the in-route HUD");
        for (int i = 0; i < 59; i++) game.advance();
        check(game.flightRouteProgress() < 0 && game.flightProgress() < 0, "jump-lock lead-in has not reached the route");
        game.advance();
        check(game.flightRouteProgress() == 0 && game.flightProgress() == 0, "jump unlock coincides exactly with the authored route start");
        for (int i = 0; i < 199; i++) game.advance();
        check(game.flightProgress() >= 0 && game.flightProgress() < 1, "flight remains before route endpoint");
        game.advance();
        check(game.flightProgress() < 0 && game.flightRouteProgress() == 1, "flight ends at route endpoint");
        var shortMap = map(1, 0, 10, new FlightPath(0, 50, 10, 50));
        var shortFlight = new MatchEngine(new MatchSession(UUID.randomUUID(), 0, shortMap),
                settings(new FlightRules(true, false, 160, 512, 3, 52)), List.of(player), 3);
        check(shortFlight.flightDurationTicks() == 62, "short routes still include the entire jump wait plus travel");
        for (int i = 0; i < 59; i++) shortFlight.advance();
        check(shortFlight.flightRouteProgress() < 0 && shortFlight.combatTicks() < shortFlight.flightDurationTicks(),
                "short route cannot force an exit before jump unlock");
        shortFlight.advance();
        check(shortFlight.flightRouteProgress() == 0, "short route starts only after the full wait");
        shortFlight.advance();
        check(shortFlight.flightRouteProgress() < 1, "short route remains airborne until its rounded travel duration");
        shortFlight.advance();
        check(shortFlight.flightRouteProgress() == 1 && shortFlight.flightProgress() < 0, "short route ends after jump unlock and travel");
        var edge = new Vec3(5, -2, 3);
        check(MatchBoundaryService.constrain(List.of(map), WORLD, 99, 50, edge).equals(new Vec3(1, -2, 3)),
                "outward movement clips but vertical motion and edge sliding remain");
        check(MatchBoundaryService.constrain(List.of(map), WORLD, 100, 50, edge).equals(new Vec3(0, -2, 3)),
                "exact edge cannot drift outward");
        check(MatchBoundaryService.constrain(List.of(map), WORLD, -1, 50, edge).equals(edge), "outside-to-inside remains allowed");
        check(MatchBoundaryService.constrain(List.of(map, map(2, 40, 80, null)), WORLD, 79, 50, edge)
                .equals(new Vec3(1, -2, 3)), "overlapping maps each constrain their members");
        check(MatchBoundaryService.constrain(List.of(map), ResourceLocation.parse("minecraft:the_nether"),
                99, 50, edge).equals(edge), "other dimensions remain unchanged");
        System.out.println("FlightBoundaryChecks: " + checks + " focused checks passed; no game launched.");
    }
    private static Settings settings(FlightRules flight) {
        var s = MatchConfigModel.defaults(1);
        return new Settings(1, s.name(), 1, 100, 1, 0, s.maxMatchSeconds(), 60, false, false, true, false,
                s.phases(), s.bombingRules(), s.flowRules(), true, true, flight);
    }
    private static MapDefinition map(long id, double minX, double maxX, FlightPath path) {
        return new MapDefinition(id, "Map", WORLD, minX, 0, maxX, 100,
                new Circle((minX + maxX) / 2, 50, Math.min(20, (maxX - minX) / 2)), 1, List.of(), path);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
