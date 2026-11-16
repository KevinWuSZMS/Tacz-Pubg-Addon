package com.example.examplemod.zones.match;

import carpet.patches.EntityPlayerMPFake;
import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.match.bot.MatchBotService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runs real Carpet death/respawn and ownership cleanup in the isolated GameTest server. */
@GameTestHolder("combat_dbno")
@PrefixGameTestTemplate(false)
public final class BotLifecycleGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(9_300_000);

    @GameTest(template = "empty", templateNamespace = "combat_dbno", timeoutTicks = 160)
    public static void deadOwnedBotRespawnsAtStagingAndRemainsUntilMatchEnd(GameTestHelper helper) throws Exception {
        Fixture f = new Fixture(helper, false);
        f.later(80, () -> {
            f.start();
            ServerPlayer original = f.killFirst();
            f.later(4, () -> {
                ServerPlayer replacement = f.server.getPlayerList().getPlayer(original.getUUID());
                helper.assertTrue(replacement instanceof EntityPlayerMPFake && replacement != original,
                        "Dead match bot did not receive a new Carpet player through vanilla respawn");
                helper.assertTrue(replacement.isAlive() && replacement.isSpectator()
                                && MatchBotService.owned(f.server, replacement.getUUID()),
                        "Respawned bot must stay online as an owned, living spectator");
                helper.assertTrue(!MatchService.alive(f.server, f.map.id(), replacement.getUUID()),
                        "Spectator respawn incorrectly returned the eliminated bot to the match");
                helper.assertTrue(f.map.stagingSpawn().contains(replacement.getX(), replacement.getZ())
                                && replacement.position().distanceToSqr(f.positions.values().iterator().next()) < .01,
                        "Spectator did not return to its original reserved staging position");
                f.later(5, () -> {
                    helper.assertTrue(f.server.getPlayerList().getPlayer(replacement.getUUID()) == replacement,
                            "A later tick disconnected the retained spectator");
                    MatchService.end(f.server, f.map.id());
                    f.later(3, () -> { f.assertRemoved(); f.close(); helper.succeed(); });
                });
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", timeoutTicks = 160)
    public static void endingDuringDeathClearsPendingBotRespawn(GameTestHelper helper) throws Exception {
        Fixture f = new Fixture(helper, false);
        f.later(80, () -> {
            f.start(); f.killFirst();
            MatchService.end(f.server, f.map.id());
            f.later(4, () -> { f.assertRemoved(); f.close(); helper.succeed(); });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", timeoutTicks = 160)
    public static void naturalVictoryRemovesSurvivorAndEliminatedBot(GameTestHelper helper) throws Exception {
        Fixture f = new Fixture(helper, true);
        f.later(80, () -> {
            f.start(); f.killFirst();
            f.later(5, () -> {
                helper.assertTrue(MatchService.session(f.server, f.map.id()) == null,
                        "Final opposing team elimination did not naturally end the match");
                f.assertRemoved(); f.close(); helper.succeed();
            });
        });
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final MinecraftServer server;
        final MatchRuntimeState state;
        final MapZoneModel.MapDefinition map;
        final boolean autoEnd;
        final Map<UUID, Vec3> positions = new LinkedHashMap<>();
        final List<UUID> bodies = new ArrayList<>();
        final UUID request;
        MatchBotService.Preparation prepared;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper, boolean autoEnd) throws Exception {
            this.helper = helper; this.autoEnd = autoEnd; server = helper.getLevel().getServer();
            BlockPos base = helper.absolutePos(BlockPos.ZERO);
            long id = MAP_IDS.getAndIncrement();
            map = new MapZoneModel.MapDefinition(id, "bot lifecycle fixture", helper.getLevel().dimension().location(),
                    base.getX() - 40, base.getZ() - 40, base.getX() + 40, base.getZ() + 40,
                    new MapZoneModel.Circle(base.getX(), base.getZ(), 30), 0, List.of(), null,
                    MapVisualStyle.DEFAULT, new MapZoneModel.Circle(base.getX() + 2, base.getZ() + 2, 4));
            var field = MatchService.class.getDeclaredField("STATES"); field.setAccessible(true);
            var states = (Map<MinecraftServer, MatchRuntimeState>) field.get(null);
            state = states.computeIfAbsent(server, ignored -> new MatchRuntimeState());
            List<MatchBotService.BotSpec> specs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                BlockPos feet = base.offset(1 + i * 2, 1, 1);
                helper.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
                UUID planned = UUID.randomUUID();
                positions.put(planned, Vec3.atBottomCenterOf(feet));
                specs.add(new MatchBotService.BotSpec(planned, "fixture " + i, "team:" + i, "scavenger", "normal"));
            }
            request = MatchBotService.prepare(server, map, specs, positions, result -> prepared = result);
        }
        void start() {
            helper.assertTrue(prepared != null && prepared.error().isEmpty() && prepared.bots().size() == 2,
                    "Carpet bot preparation failed: " + (prepared == null ? "not completed" : prepared.error()));
            var roster = prepared.bots().stream().map(b -> new MatchEngine.Participant(b.actualId(), b.name(), b.teamId())).toList();
            bodies.addAll(roster.stream().map(MatchEngine.Participant::id).toList());
            var d = MatchConfigModel.defaults(map.id());
            var settings = new MatchConfigModel.Settings(map.id(), "bot lifecycle fixture", 1, 20, 1, 0, 2400, 60,
                    false, autoEnd, false, false, d.phases(), d.bombingRules(), d.flowRules(), false, false,
                    new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52));
            MatchSession session = state.start(map, settings, roster, 91);
            MatchBotService.bind(server, request, session, roster);
            for (var bot : prepared.bots()) MatchBotService.behavior(server, bot.name(), "hold", null, null);
        }
        ServerPlayer killFirst() {
            ServerPlayer player = server.getPlayerList().getPlayer(bodies.getFirst());
            player.setHealth(0); player.die(player.damageSources().genericKill());
            helper.assertTrue(!MatchService.alive(server, map.id(), player.getUUID()), "Actual death did not eliminate the bot");
            helper.assertTrue(player.getHealth() == 0, "Carpet executed its post-death health reset instead of retaining the corpse for respawn");
            return player;
        }
        void assertRemoved() {
            for (UUID id : bodies) helper.assertTrue(server.getPlayerList().getPlayer(id) == null
                    && !MatchBotService.owned(server, id), "Round end left an owned Carpet body online");
        }
        void later(int delay, Runnable action) {
            helper.runAfterDelay(delay, () -> {
                try { action.run(); }
                catch (RuntimeException | AssertionError error) { close(); throw error; }
            });
        }
        void close() {
            if (state.session(map.id()) != null) MatchService.end(server, map.id());
            MatchBotService.cancel(server, request);
            state.forgetDeletedMap(map.id());
        }
    }
}
