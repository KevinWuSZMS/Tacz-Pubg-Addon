package com.example.examplemod.zones.match;

import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.match.MatchConfigModel.Phase;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.UUID;

/** Pure roster, phase, geometry and result checks. No game, player entity or world is launched. */
public final class MatchGameChecks {
    private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation OTHER = ResourceLocation.parse("minecraft:the_nether");
    private static final MatchEngine.Participant A = person(1, "Alice", "alpha");
    private static final MatchEngine.Participant B = person(2, "Bob", "alpha");
    private static final MatchEngine.Participant C = person(3, "Cara", "beta");
    private static int checks;
    public static void main(String[] args) {
        phaseBoundariesAndPause();
        continuousCircleAnnouncement();
        rosterAndResults();
        rosterDamageIsolation();
        operatorDamageIsolation();
        operatorModeApproval();
        disconnectAndElimination();
        circleContainment();
        multiMapAndStatus();
        System.out.println("MatchGameChecks: " + checks + " assertions passed");
    }
    private static void continuousCircleAnnouncement() {
        var game = engine(map(1), settings(1, 1, false, true, 1, 60, 1,
                List.of(new Phase(1, 2, .5, 1, true), new Phase(1, 2, .5, 2, true))), List.of(A), 71);
        Circle fixedTarget = null;
        int previousPhase = 0;
        for (int tick = 0; tick < 160; tick++) {
            var status = game.status();
            var target = MatchCirclePreview.target(status, 1);
            if (status.stage().equals("waiting") || status.stage().equals("shrinking")) {
                check(target != null, "next circle stays announced on every waiting/shrinking tick, including phase boundaries");
                if (status.phase() != previousPhase) { fixedTarget = target; previousPhase = status.phase(); }
                check(target.equals(fixedTarget), "announced safe circle remains fixed while the blue circle moves");
                var snapshot = new com.example.examplemod.zones.MapOverlayPayloads.MapSnapshot(tick, WORLD,
                        com.example.examplemod.zones.MapZoneModel.toJson(game.projectedMap()), true, target);
                check(snapshot.nextCircle().equals(status.targetCircle()), "same-tick map snapshot carries the exact phase target");
                if (tick == 55 || tick == 115) {
                    game.pause(); tick(game, 200);
                    check(MatchCirclePreview.target(game.status(), 1).equals(target),
                            "long pause retains the target beyond the old HUD expiry interval");
                    game.resume();
                }
            } else check(target == null, "preparation and final phase have no unannounced future circle");
            game.advance();
        }
        game.stop();
        check(MatchCirclePreview.target(game.status(), 1) == null, "stopping a match clears its next-circle announcement");
    }
    private static void phaseBoundariesAndPause() {
        Settings settings = settings(1, 1, false, true, 2, 10, 1, List.of(new Phase(2, 2, .5, 3, false)));
        MatchEngine game = engine(map(1), settings, List.of(A), 1);
        check(game.status().stage().equals("preparing") && game.status().secondsRemaining() == 2, "saved preparation countdown begins before battle");
        check(game.damageAt(A.id(), WORLD, 20, 0, true) == 0, "preparation never applies blue damage");
        tick(game, 39);
        check(game.status().stage().equals("preparing") && game.status().secondsRemaining() == 1, "preparation does not end early");
        tick(game, 1);
        check(game.status().stage().equals("waiting") && game.status().phase() == 1, "exact preparation boundary enters first wait");
        check(game.status().currentCircle() == null && game.projectedMap().safeZone() == null,
                "first announced wait has no damaging blue boundary");
        near(game.status().targetCircle().radius(), 5, "target is authored as a ratio of previous radius");
        check(game.damageAt(A.id(), WORLD, 20, 0, true) == 0, "first announced wait allows looting across the entire map");
        check(game.damageAt(UUID.randomUUID(), WORLD, 20, 0, true) == 0, "bystanders outside the roster never take match damage");
        check(game.damageAt(A.id(), OTHER, 20, 0, true) == 0 && game.damageAt(A.id(), WORLD, 200, 0, true) == 0,
                "damage stays in the match dimension and rectangle");
        tick(game, 40);
        check(game.status().stage().equals("shrinking"), "exact wait boundary begins shrinking");
        near(game.status().currentCircle().radius(), Math.hypot(100, 100), "first blue boundary covers the map before closing");
        tick(game, 20);
        near(game.status().currentCircle().radius(), (Math.hypot(100, 100) + 5) / 2, "radius is linearly interpolated at half shrink time");
        long frozen = game.elapsedTicks(); Circle frozenCircle = game.status().currentCircle();
        game.pause(); tick(game, 100);
        check(game.status().stage().equals("paused") && game.elapsedTicks() == frozen && game.status().currentCircle().equals(frozenCircle),
                "pause freezes countdown and circle movement");
        check(game.damageAt(A.id(), WORLD, 20, 0, true) == 0, "paused matches apply no blue damage");
        game.resume(); tick(game, 20);
        check(game.status().stage().equals("final"), "last shrink transitions to final hold");
        near(game.status().currentCircle().radius(), 5, "final hold uses the fully reached target");
        check(game.session().map().safeZone().radius() == 10, "moving circles do not mutate the authored map snapshot");
        tick(game, 120);
        check(game.finished() && com.example.examplemod.localization.TestLanguages.chinese(game.status().result()).contains("时间上限") && game.status().stage().equals("finished"),
                "single-player test still reaches a time-limit draw after battle time, excluding preparation");

        Settings disabled = settings(1, 1, false, false, 0, 4, 1, List.of(new Phase(1, 1, .1, 99, true)));
        MatchEngine noBlue = engine(map(1), disabled, List.of(A), 9);
        tick(noBlue, 60);
        check(noBlue.status().stage().equals("final") && noBlue.status().phase() == 0 && noBlue.status().currentCircle() == null,
                "disabled blue zone does not shrink while the battle timer advances");
        check(noBlue.projectedMap().safeZone() == null && noBlue.projectedMap().blueDamagePerSecond() == 0,
                "disabled blue zone projection hides map/world blue effects");
        tick(noBlue, 20);
        check(noBlue.finished(), "disabled blue zone does not disable the explicit match timeout");
    }
    private static void rosterAndResults() {
        Settings solo = settings(1, 1, true, true, 0, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        MatchEngine sameScoreboard = engine(map(1), solo, List.of(A, B), 1);
        check(sameScoreboard.status().teamsAlive() == 2 && !sameScoreboard.blocksFriendlyFire(A.id(), B.id()),
                "solo mode gives each player an independent frozen team despite equal scoreboard input");
        sameScoreboard.eliminate(A.id());
        check(!sameScoreboard.finished(), "a death event does not immediately crown another player");
        sameScoreboard.advance();
        check(sameScoreboard.finished() && com.example.examplemod.localization.TestLanguages.chinese(sameScoreboard.status().result()).contains("Bob"), "last remaining solo player wins at centralized tick judgment");
        MatchEngine mutual = engine(map(1), solo, List.of(A, B), 1);
        mutual.eliminate(A.id()); mutual.eliminate(B.id()); mutual.advance();
        check(mutual.status().playersAlive() == 0 && com.example.examplemod.localization.TestLanguages.chinese(mutual.status().result()).contains("平局") && !com.example.examplemod.localization.TestLanguages.chinese(mutual.status().result()).contains("获胜"),
                "all deaths in the same tick produce a draw rather than a premature winner");
        Settings team = settings(1, 2, true, true, 0, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        rejects(() -> engine(map(1), team, List.of(A, B), 1), "automatic victory requires at least two different teams");
        MatchEngine grouped = engine(map(1), team, List.of(A, B, C), 1);
        check(grouped.status().teamsAlive() == 2 && grouped.blocksFriendlyFire(A.id(), B.id()) && !grouped.blocksFriendlyFire(A.id(), C.id()),
                "friendly-fire prohibition uses the frozen match roster only");
        grouped.eliminate(C.id()); grouped.advance();
        check(com.example.examplemod.localization.TestLanguages.chinese(grouped.status().result()).contains("Alice") && com.example.examplemod.localization.TestLanguages.chinese(grouped.status().result()).contains("Bob"), "last surviving team wins together");
        Settings allowed = new Settings(1, "test", 1, 20, 2, 0, 10, 1, true, false, true, true, team.phases());
        check(!engine(map(1), allowed, List.of(A, B, C), 2).blocksFriendlyFire(A.id(), B.id()), "enabled friendly fire adds no extra cancellation");
        rejects(() -> engine(map(1), team, List.of(A, B, person(4, "Dan", "alpha"), C), 1), "oversized scoreboard team is rejected before start");
        rejects(() -> engine(map(1), solo, List.of(A, A), 1), "duplicate roster UUIDs are rejected");
        java.util.ArrayList<MatchEngine.Participant> bigRoster = new java.util.ArrayList<>();
        for (int i = 1; i <= 19; i++) bigRoster.add(person(i, "Player_" + i + "_long_name", "large"));
        MatchEngine.Participant opponent = person(100, "Opponent", "opposition"); bigRoster.add(opponent);
        Settings largeTeam = settings(1, 19, true, true, 0, 10, 1, team.phases());
        MatchEngine large = engine(map(1), largeTeam, bigRoster, 1); large.eliminate(opponent.id()); large.advance();
        check(large.status().result().length() <= com.example.examplemod.localization.Texts.MAX_MESSAGE_LENGTH && com.example.examplemod.localization.TestLanguages.chinese(large.status().result()).contains("19 人"),
                "large-team results remain inside network string bounds and preserve the winning player count");
    }
    private static void operatorModeApproval() {
        var enabled = com.example.examplemod.operatorpermissions.OperatorPermissions.defaults()
                .with(com.example.examplemod.operatorpermissions.OperatorPermissions.Permission.KEEP_GAME_MODE, true);
        MatchRuntimeState state = new MatchRuntimeState();
        Settings settings = settings(1, 1, false, true, 0, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        MatchSession session = state.start(map(1), settings, List.of(A), 1);
        UUID approval = enabled.keepGameMode() ? session.sessionId() : null;
        check(state.hasActiveRosterSession(A.id(), approval), "OP mode approval is captured for the starting roster session");
        var disabled = enabled.with(com.example.examplemod.operatorpermissions.OperatorPermissions.Permission.KEEP_GAME_MODE, false);
        check(!disabled.keepGameMode() && state.hasActiveRosterSession(A.id(), approval),
                "disabling keep-mode for future starts cannot revoke an approved running roster and eliminate its operator");
        check(!state.hasActiveRosterSession(B.id(), approval) && !state.hasActiveRosterSession(A.id(), UUID.randomUUID()),
                "mode approval is limited to its exact roster member and session");
        state.end(1);
        check(!state.hasActiveRosterSession(A.id(), approval), "ending a match invalidates its mode approval");
        state.start(map(1), settings, List.of(A), 2);
        check(!state.hasActiveRosterSession(A.id(), approval), "a later match on the same map never inherits stale mode approval");
        check(!new MatchRuntimeState().hasActiveRosterSession(A.id(), approval), "server restart cannot reactivate a persisted approval");
    }
    private static void operatorDamageIsolation() {
        Settings team = settings(1, 2, false, true, 2, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        MatchEngine game = engine(map(1), team, List.of(A, B, C), 1);
        UUID outsider = new UUID(0, 1000);
        check(game.blocksPlayerDamage(outsider, A.id(), true, false, false), "outside target privilege alone cannot bypass preparation");
        check(game.blocksPlayerDamage(outsider, A.id(), false, true, true), "preparation and friendly-fire bypass cannot grant outside target permission");
        check(!game.blocksPlayerDamage(outsider, A.id(), true, false, true), "authorized outside operator can damage a permitted target during preparation");
        check(game.blocksPlayerDamage(A.id(), B.id(), true, false, true), "OP target and preparation privileges retain separate friendly-fire protection");
        check(!game.blocksPlayerDamage(A.id(), B.id(), false, true, true), "an ordinary roster attack can separately opt out of preparation and friendly-fire protection");
        tick(game, 40);
        check(!game.blocksPlayerDamage(outsider, A.id(), true, false, false), "outside target privilege works in active battle without preparation bypass");
        check(game.blocksPlayerDamage(outsider, A.id()), "normal outsiders still cannot damage participants");
        game.pause();
        check(game.blocksPlayerDamage(outsider, A.id(), true, true, true), "even all OP attack privileges preserve paused-match protection");
        game.resume(); game.eliminate(A.id());
        check(game.blocksPlayerDamage(outsider, A.id(), true, true, true), "OP attack privileges never reactivate an eliminated victim");
        check(!game.blocksPlayerDamage(A.id(), C.id(), true, false, false), "eliminated OP may intervene only with the explicit target privilege");
        check(game.blocksPlayerDamage(A.id(), B.id(), true, false, false), "eliminated OP retains the frozen team for friendly-fire protection");
        check(!game.blocksPlayerDamage(A.id(), B.id(), true, true, false), "eliminated OP requires the separate friendly-fire bypass to damage a teammate");
        check(game.blocksPlayerDamage(A.id(), C.id(), false, true, true), "other privileges cannot revive an eliminated attacker's normal combat rights");
        check(game.blocksPlayerDamage(C.id(), outsider, true, true, true), "OP target privileges cannot attack an outsider from a protected roster");
    }
    private static void disconnectAndElimination() {
        Settings settings = settings(1, 1, false, true, 0, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        MatchEngine game = engine(map(1), settings, List.of(A), 1);
        game.presence(A.id(), new MatchEngine.Presence(false, false, false, null, 0, 0)); tick(game, 19);
        check(game.alive(A.id()), "disconnected player survives within the configured grace window");
        game.presence(A.id(), online(WORLD, 0, 0, true)); game.advance();
        check(game.alive(A.id()), "reconnect before the deadline restores presence without a new registration");
        game.presence(A.id(), new MatchEngine.Presence(false, false, false, null, 0, 0)); tick(game, 20);
        check(!game.alive(A.id()), "disconnect deadline eliminates the original participant");
        game.presence(A.id(), online(WORLD, 0, 0, true));
        check(!game.alive(A.id()), "reconnecting or respawning cannot resurrect an eliminated participant");
        for (MatchEngine.Presence bad : List.of(online(OTHER, 0, 0, true), online(WORLD, 101, 0, true),
                online(WORLD, 0, 0, false), new MatchEngine.Presence(true, false, true, WORLD, 0, 0))) {
            MatchEngine invalid = engine(map(1), settings, List.of(A), 1); invalid.presence(A.id(), bad);
            check(!invalid.alive(A.id()), "dimension escape, bounds escape, cheating mode and death eliminate players");
        }
    }
    private static void rosterDamageIsolation() {
        Settings team = settings(1, 2, false, true, 2, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        MatchEngine game = engine(map(1), team, List.of(A, B, C), 1);
        UUID outsider = new UUID(0, 1000), anotherOutsider = new UUID(0, 1001);
        check(game.blocksPlayerDamage(A.id(), C.id()), "preparation protects registered participants");
        tick(game, 40);
        check(!game.blocksPlayerDamage(A.id(), C.id()), "battle enables opposing participant combat");
        game.pause();
        check(game.blocksPlayerDamage(A.id(), C.id()), "pause protects participants while bot actions and match time stop");
        game.resume();
        check(game.blocksPlayerDamage(A.id(), B.id()), "alive same-team participants obey the friendly-fire prohibition");
        check(game.blocksPlayerDamage(outsider, A.id()) && game.blocksPlayerDamage(A.id(), outsider),
                "late arrivals cannot attack the roster or be attacked by a registered participant");
        check(!game.blocksPlayerDamage(outsider, anotherOutsider), "combat between two outsiders is untouched");
        game.eliminate(A.id());
        game.presence(A.id(), online(WORLD, 0, 0, true));
        check(game.blocksPlayerDamage(A.id(), C.id()) && game.blocksPlayerDamage(C.id(), A.id()),
                "eliminated players remain isolated after respawn even without forced spectator mode");
        Settings noSpectate = new Settings(1, "no spectator", 1, 20, 2, 0, 10, 1, true, false, false, true, team.phases());
        MatchEngine allowed = engine(map(1), noSpectate, List.of(A, B, C), 2);
        check(!allowed.blocksPlayerDamage(A.id(), B.id()), "friendly-fire enabled adds no restriction to two alive teammates");
        allowed.eliminate(A.id());
        check(allowed.blocksPlayerDamage(A.id(), B.id()), "turning spectator and friendly-fire restrictions off never revives an eliminated attacker");
        allowed.stop();
        check(!allowed.blocksPlayerDamage(A.id(), B.id()) && !allowed.blocksPlayerDamage(outsider, B.id()),
                "explicitly ended matches release their damage isolation");
        Settings automatic = settings(1, 1, true, true, 0, 10, 1, team.phases());
        MatchEngine ended = engine(map(1), automatic, List.of(A, B), 3);
        ended.eliminate(A.id()); ended.advance();
        check(!ended.blocksPlayerDamage(A.id(), B.id()), "automatically finished matches also release damage isolation");
        MatchRuntimeState state = new MatchRuntimeState();
        state.start(map(1), team, List.of(A, B, C), 1);
        MatchEngine.Participant remoteA = person(4, "Dan", "remote"), remoteB = person(5, "Eve", "remote");
        state.start(map(2), settings(2, 2, false, true, 0, 10, 1, team.phases()), List.of(remoteA, remoteB), 2);
        check(state.blocksPlayerDamage(A.id(), remoteA.id()) && state.blocksPlayerDamage(remoteB.id(), C.id()),
                "players in different matches cannot damage each other's roster");
        check(!state.blocksPlayerDamage(outsider, anotherOutsider), "multi-map arbitration leaves all-outsider combat alone");
        state.end(1); state.end(2);
        check(!state.blocksPlayerDamage(A.id(), remoteA.id()), "ending both matches removes all additional player-damage restrictions");
    }
    private static void circleContainment() {
        List<Phase> phases = List.of(new Phase(0, 1, .65, 1, true), new Phase(0, 1, .5, 2, true), new Phase(0, 1, .001, 3, true));
        MapDefinition edge = new MapDefinition(1, "edge", WORLD, -100, -100, 100, 100, new Circle(100, 0, 10), 1, List.of());
        for (int seed = 0; seed < 100; seed++) {
            MatchEngine game = engine(edge, settings(1, 1, false, true, 0, 10, 1, phases), List.of(A), seed);
            MatchEngine repeat = engine(edge, settings(1, 1, false, true, 0, 10, 1, phases), List.of(A), seed);
            for (int phase = 0; phase < phases.size(); phase++) {
                Circle from = game.status().currentCircle(), to = game.status().targetCircle();
                check(edge.contains(to.centerX(), to.centerZ()) && to.radius() >= .5,
                        "random target centers stay inside the map and never shrink below half a metre");
                check(Math.hypot(to.centerX() - from.centerX(), to.centerZ() - from.centerZ()) + to.radius() <= from.radius() + 1e-8,
                        "the current circle fully contains the target circle");
                check(to.equals(repeat.status().targetCircle()), "the frozen match seed deterministically generates random target centers");
                tick(game, 10); Circle midway = game.status().currentCircle();
                near(midway.centerX(), (from.centerX() + to.centerX()) / 2, "circle center interpolates linearly");
                tick(game, 10); tick(repeat, 20);
            }
        }
    }
    private static void multiMapAndStatus() {
        Settings settings = settings(1, 1, true, true, 0, 10, 1, List.of(new Phase(1, 1, .5, 1, false)));
        MatchRuntimeState state = new MatchRuntimeState(); state.start(map(1), settings, List.of(A, B), 1);
        Settings second = settings(2, 1, true, true, 0, 10, 1, settings.phases());
        rejects(() -> state.start(map(2), second, List.of(A, C), 2), "the same UUID cannot participate in two matches");
        check(state.session(2) == null, "rejected multi-map registration is atomic");
        state.engine(1).eliminate(A.id()); state.advanceTick();
        check(state.session(1) == null && state.status(1).stage().equals("finished") && state.activeMaps().isEmpty(),
                "finished status is retained while the map editing lock and damaging projection are released");
        check(new MatchRuntimeState().status(1) == null, "server restart never resumes a match or reactivates damage");
        state.start(map(1), settings, List.of(A, B), 1); state.end(1);
        check(state.status(1).stage().equals("stopped") && !com.example.examplemod.localization.TestLanguages.chinese(state.status(1).result()).contains("获胜"), "explicit cancellation has no winner");
    }
    private static Settings settings(long map, int teamSize, boolean autoEnd, boolean blue, int prep, int maximum, int grace, List<Phase> phases) {
        return new Settings(map, "test", 1, 20, teamSize, prep, maximum, grace, blue, autoEnd, true, false, phases);
    }
    private static MatchEngine engine(MapDefinition map, Settings settings, List<MatchEngine.Participant> roster, long seed) {
        return new MatchEngine(new MatchSession(UUID.randomUUID(), 0, map), settings, roster, seed);
    }
    private static MatchEngine.Participant person(long id, String name, String team) { return new MatchEngine.Participant(new UUID(0, id), name, team); }
    private static MatchEngine.Presence online(ResourceLocation dimension, double x, double z, boolean eligible) {
        return new MatchEngine.Presence(true, true, eligible, dimension, x, z);
    }
    private static MapDefinition map(long id) { return new MapDefinition(id, "map", WORLD, -100, -100, 100, 100, new Circle(0, 0, 10), 1, List.of()); }
    private static void tick(MatchEngine game, int count) { for (int i = 0; i < count; i++) game.advance(); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError(message);
    }
}
