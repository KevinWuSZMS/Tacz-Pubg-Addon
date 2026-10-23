package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapOverlayPayloads;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.Circle;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Exact tick boundaries, late-join payloads, pause and coherent map publication. */
public final class CircleClockChecks {
    private static int checks;
    public static void main(String[] args) {
        var world = ResourceLocation.parse("minecraft:overworld");
        var map = new MapZoneModel.MapDefinition(1, "clock", world, -100, -100, 100, 100,
                new Circle(0, 0, 60), 1, List.of(), null);
        var rules = new MatchConfigModel.Settings(1, "clock", 1, 20, 1, 0, 120, 60, true, false, true, false,
                List.of(new MatchConfigModel.Phase(3, 7, .5, 1, true)), MatchConfigModel.BombingRules.defaults(),
                MatchConfigModel.FlowRules.defaults().withFirstCircleDelay(0));
        var player = new MatchEngine.Participant(new UUID(0, 1), "one", "one");
        var game = new MatchEngine(new MatchSession(new UUID(3, 7), 0, map), rules, List.of(player), 19);
        var initial = game.status();
        check(initial.currentCircle() == null && initial.phaseStartCircle() != null, "first waiting exposes real starting circle without painting blue");
        check(initial.stageDurationTicks() == 60 && initial.stageElapsedTicks() == 0, "initial wait uses exact tick duration");
        for (int i = 0; i < 80; i++) game.advance();
        var mid = game.status();
        check(mid.stage().equals("shrinking") && mid.stageElapsedTicks() == 20 && mid.stageDurationTicks() == 140, "contraction records exact tick cursor");
        check(mid.phaseStartCircle().equals(initial.phaseStartCircle()) && !mid.phaseStartCircle().equals(mid.currentCircle()), "late join never substitutes current wall for true start");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
        try {
            MatchConfigPayloads.writeStatus(buffer, mid);
            check(MatchConfigPayloads.readStatus(buffer).equals(mid) && !buffer.isReadable(), "late-join status round trip retains full circle clock");
            var packet = new MapOverlayPayloads.MapSnapshot(4, world, MapZoneModel.toJson(game.projectedMap()), true,
                    MatchCirclePreview.target(mid, 1), mid.flightProgress(), mid);
            MapOverlayPayloads.MapSnapshot.CODEC.encode(buffer, packet);
            check(MapOverlayPayloads.MapSnapshot.CODEC.decode(buffer).equals(packet) && !buffer.isReadable(), "map geometry and clock travel atomically");
        } finally { buffer.release(); }
        var exact = MatchCircleClock.sample(mid, 0);
        near(exact.current().radius(), mid.currentCircle().radius(), "map and bar reconstruct the authoritative snapshot circle");
        check(exact.remainingTicks() == 120, "remaining tick count is not rounded seconds");
        var prediction = MatchCircleClock.sample(mid, 500);
        near(prediction.elapsedTicks(), 30, "network prediction bounded to expected publication interval");
        game.pause(); for (int i = 0; i < 300; i++) game.advance();
        var paused = game.status();
        check(paused.paused() && paused.underlyingStage().equals("shrinking") && paused.stageElapsedTicks() == 20,
                "pause retains underlying contraction and frozen cursor");
        var frozen = MatchCircleClock.sample(paused, 300);
        check(frozen.current().equals(paused.currentCircle()) && frozen.remainingTicks() == 120 && frozen.animationTicks() == paused.matchTicks(),
                "pause freezes circle, timer, player scale and conveyor epoch");
        game.resume(); game.advance(); check(game.status().stageElapsedTicks() == 21, "resume advances from saved tick without a jump");
        try {
            new MapOverlayPayloads.MapSnapshot(4, world, MapZoneModel.toJson(game.projectedMap()), true,
                    MatchCirclePreview.target(mid, 1), mid.flightProgress(), mid);
            throw new AssertionError("Mixed snapshot accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("CircleClockChecks: " + checks + " passed; no game launched.");
    }
    private static void near(double a, double b, String message) { check(Math.abs(a - b) < 1e-8, message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
