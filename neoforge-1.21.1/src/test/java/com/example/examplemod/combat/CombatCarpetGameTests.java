package com.example.examplemod.combat;

import carpet.patches.EntityPlayerMPFake;
import carpet.patches.NetHandlerPlayServerFake;
import com.example.examplemod.movement.MovementService;
import com.example.examplemod.movement.MovementState.Mode;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises Carpet's actual die override, queued disconnects, server ticks and rescue validation. */
@GameTestHolder("combat_dbno")
@PrefixGameTestTemplate(false)
public final class CombatCarpetGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(9_200_000);

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 1400)
    public static void carpetDownedPlayersRemainOnlineAndCanBeRevivedRepeatedly(GameTestHelper helper) throws Exception {
        Fixture f = new Fixture(helper);
        // Use real damage after Minecraft's normal 60-tick login protection has expired.
        f.later(65, () -> {
            f.knock(1);
            // Carpet's old kill() queues a disconnect; an immediate isAlive assertion alone misses it.
            f.later(3, () -> {
                f.assertDowned(1);
                f.measureLoss(false, firstStationary -> f.measureLoss(true, firstMoving -> {
                    helper.assertTrue(firstStationary > 0 && firstMoving > 0,
                            "The real server tick did not reduce the independent downed health pool");
                    CombatService.rescue(f.enemy, f.victim.getUUID(), false);
                    helper.assertTrue(!CombatService.rescuing(f.enemy), "An enemy was allowed to rescue the downed player");
                    f.faceVictim();
                    CombatService.rescue(f.ally, f.victim.getUUID(), false);
                    helper.assertTrue(CombatService.rescuing(f.ally), "A nearby teammate could not begin rescue");
                    f.ally.setPos(f.ally.position().add(1, 0, 0));
                    f.later(2, () -> {
                        helper.assertTrue(!CombatService.rescuing(f.ally), "Moving away did not cancel rescue");
                        f.ally.setPos(f.allyOrigin);
                        f.faceVictim();
                        CombatService.rescue(f.ally, f.victim.getUUID(), false);
                        helper.assertTrue(CombatService.rescuing(f.ally), "A valid rescue could not restart after cancellation");
                        f.later((int) f.setting("rescue.ticks") + 2, () -> {
                            helper.assertTrue(!CombatService.downed(f.victim) && !CombatService.rescuing(f.ally),
                                    "The completed rescue left either player action-locked");
                            helper.assertTrue(f.online(f.victim) && f.game.alive(f.victim.getUUID()),
                                    "The revived Carpet player was removed or remained eliminated");
                            helper.assertTrue(Math.abs(f.victim.getHealth() - f.victim.getMaxHealth()
                                    * f.setting("rescue.health_percent") / 100) < .001,
                                    "Rescue restored the wrong amount of health");
                            helper.assertTrue(f.victim.getForcedPose() == null, "Rescue left the forced crawling pose active");
                            f.knock(2);
                            f.later(3, () -> f.measureLoss(false, secondStationary -> f.measureLoss(true, secondMoving -> {
                                helper.assertTrue(Math.abs(firstStationary - secondStationary) < .001,
                                        "Stationary bleeding unexpectedly accelerated with knock count");
                                helper.assertTrue(Math.abs(firstMoving * f.setting("dbno.bleed_first_seconds")
                                        - secondMoving * f.setting("dbno.bleed_second_seconds")) < .05,
                                        "Repeated moving DBNO did not apply its configured faster bleed tier");
                                f.assertDowned(2);
                                helper.assertTrue(f.capture(f.victim).events.stream()
                                        .filter(e -> e.victim().id().equals(f.victim.getUUID()) && e.type().equals("knock")).count() == 2,
                                        "Repeated downing did not publish exactly two knock events");
                                helper.assertTrue(f.capture(f.victim).events.stream()
                                        .noneMatch(e -> e.victim().id().equals(f.victim.getUUID()) && e.type().equals("elimination")),
                                        "A knock or rescue incorrectly published an elimination");
                                // External Carpet players still follow native removal after a true final death.
                                f.enemy.hurt(f.enemy.damageSources().generic(), 1000);
                                helper.assertTrue(!CombatService.downed(f.enemy) && !f.game.alive(f.enemy.getUUID()),
                                        "A solo player without a rescue route entered DBNO instead of direct death");
                                f.later(3, () -> {
                                    helper.assertTrue(!f.online(f.enemy), "The compatibility hook retained an unrelated external dead Carpet player");
                                    f.close();
                                    helper.succeed();
                                });
                            })));
                        });
                    });
                }));
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 600)
    public static void rescueUsesMatchCrouchAndChangingStanceCancelsIt(GameTestHelper helper) throws Exception {
        Fixture f = new Fixture(helper);
        f.later(65, () -> {
            f.knock(1);
            f.later(3, () -> {
                BlockPos ceiling = f.ally.blockPosition().above();
                var previous = helper.getLevel().getBlockState(ceiling);
                try {
                    helper.getLevel().setBlockAndUpdate(ceiling, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    MovementService.input(f.ally, Mode.PRONE, true);
                    f.ally.setPose(Pose.SWIMMING); f.faceVictim();
                    CombatService.rescue(f.ally, f.victim.getUUID(), false);
                    helper.assertTrue(!CombatService.rescuing(f.ally) && f.ally.getPose() == Pose.SWIMMING,
                            "Rescue expanded a prone rescuer into a low ceiling");
                } finally { helper.getLevel().setBlockAndUpdate(ceiling, previous); }
                f.beginRescue();
                f.later(3, () -> {
                    f.assertRescueCrouch();
                    MovementService.input(f.ally, Mode.CROUCH, true);
                    f.later(3, () -> {
                        f.assertRescueCrouch();
                        f.checkRescueStanceChanges(List.of(Mode.STAND, Mode.PRONE, Mode.SPRINT), 0, () ->
                                f.later((int) f.setting("rescue.ticks") + 2, () -> {
                                    helper.assertTrue(!CombatService.rescuing(f.ally) && !CombatService.downed(f.victim),
                                            "Rescue could not complete after cancelling and restarting for stance changes");
                                    helper.assertTrue(MovementService.mode(f.ally) == Mode.CROUCH && f.ally.getPose() == Pose.CROUCHING,
                                            "Completing rescue should leave the rescuer in the match crouch stance");
                                    helper.assertTrue(Math.abs(f.victim.getHealth() - f.victim.getMaxHealth()
                                            * f.setting("rescue.health_percent") / 100) < .001,
                                            "Rescue following stance cancellation restored the wrong health");
                                    f.close(); helper.succeed();
                                }));
                    });
                });
            });
        });
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final Object state;
        final long mapId = MAP_IDS.getAndIncrement();
        final List<EntityPlayerMPFake> players = new ArrayList<>();
        final EntityPlayerMPFake victim, ally, enemy;
        final Vec3 allyOrigin;
        final MatchEngine game;
        boolean closed;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            server = helper.getLevel().getServer();
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            Vec3 center = new Vec3(origin.getX() + 2.5, origin.getY() + 2, origin.getZ() + 2.5);
            victim = create("v", center);
            allyOrigin = center.add(2, 0, 0);
            ally = create("a", allyOrigin);
            enemy = create("e", center.add(0, 0, 4));
            var statesField = MatchService.class.getDeclaredField("STATES");
            statesField.setAccessible(true);
            Map<MinecraftServer, Object> states = (Map<MinecraftServer, Object>) statesField.get(null);
            Class<?> stateType = Class.forName("com.example.examplemod.zones.match.MatchRuntimeState");
            Constructor<?> constructor = stateType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object existing = states.get(server);
            state = existing == null ? constructor.newInstance() : existing;
            states.put(server, state);
            var map = new MapZoneModel.MapDefinition(mapId, "combat fixture", helper.getLevel().dimension().location(),
                    origin.getX() - 50, origin.getZ() - 50, origin.getX() + 50, origin.getZ() + 50,
                    new MapZoneModel.Circle(origin.getX(), origin.getZ(), 40), 0, List.of());
            var defaults = MatchConfigModel.defaults(mapId);
            var settings = new MatchConfigModel.Settings(mapId, "combat fixture", 1, 20, 2, 0, 2400, 60,
                    false, false, false, false, defaults.phases(),
                    new MatchConfigModel.BombingRules(false, .12, 60, defaults.bombingRules().projectile()),
                    defaults.flowRules(), false, false, new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52));
            Method start = stateType.getDeclaredMethod("start", MapZoneModel.MapDefinition.class,
                    MatchConfigModel.Settings.class, List.class, long.class);
            start.setAccessible(true);
            start.invoke(state, map, settings, List.of(member(victim, "A"), member(ally, "A"), member(enemy, "B")), 23L);
            game = MatchService.participantGame(server, victim.getUUID());
            if (game == null) throw new AssertionError("Combat fixture was not registered");
        }

        private MatchEngine.Participant member(ServerPlayer player, String team) {
            return new MatchEngine.Participant(player.getUUID(), player.getGameProfile().getName(), team);
        }

        private EntityPlayerMPFake create(String suffix, Vec3 position) {
            String name = "dbno" + mapId + suffix;
            var fake = EntityPlayerMPFake.respawnFake(helper.getLevel(), new GameProfile(UUID.randomUUID(), name),
                    ClientInformation.createDefault());
            players.add(fake);
            fake.fixStartingPosition = () -> fake.moveTo(position.x, position.y, position.z, 0, 0);
            var cookie = new CommonListenerCookie(fake.getGameProfile(), 0, fake.clientInformation(), false);
            server.getPlayerList().placeNewPlayer(NetHandlerPlayServerFake.DUMMY_CONNECTION, fake, cookie);
            fake.connection = new CaptureConnection(server, fake, cookie);
            fake.teleportTo(helper.getLevel(), position.x, position.y, position.z, 0, 0);
            fake.setGameMode(GameType.ADVENTURE);
            fake.getAbilities().invulnerable = false;
            fake.getAbilities().flying = false;
            fake.setNoGravity(true);
            fake.setDeltaMovement(Vec3.ZERO);
            fake.setHealth(fake.getMaxHealth());
            return fake;
        }

        double setting(String key) { return PubgSettingsService.server(server).number(key); }
        boolean online(ServerPlayer player) { return server.getPlayerList().getPlayer(player.getUUID()) == player; }
        CaptureConnection capture(ServerPlayer player) { return (CaptureConnection) player.connection; }
        CombatPayloads.Snapshot snapshot() { CombatService.push(victim); return capture(victim).snapshot; }
        void faceVictim() { ally.lookAt(EntityAnchorArgument.Anchor.EYES, victim.getBoundingBox().getCenter()); }

        void beginRescue() {
            faceVictim();
            CombatService.rescue(ally, victim.getUUID(), false);
            assertRescueCrouch();
        }

        void assertRescueCrouch() {
            helper.assertTrue(CombatService.rescuing(ally) && MovementService.mode(ally) == Mode.CROUCH
                            && ally.getPose() == Pose.CROUCHING,
                    "Rescue must actively use the synchronized match crouch stance: rescuing="
                            + CombatService.rescuing(ally) + ", mode=" + MovementService.mode(ally) + ", pose=" + ally.getPose());
        }

        void checkRescueStanceChanges(List<Mode> modes, int index, CheckedAction done) throws Exception {
            if (index == modes.size()) { done.run(); return; }
            Mode next = modes.get(index);
            MovementService.input(ally, next, true);
            helper.assertTrue(!CombatService.rescuing(ally), "Changing stance to " + next + " did not immediately cancel rescue");
            helper.assertTrue(MovementService.mode(ally) == next, "Cancelling rescue blocked the requested stance " + next);
            later(2, () -> {
                helper.assertTrue(!CombatService.rescuing(ally) && MovementService.mode(ally) == next,
                        "Rescue reasserted its crouch stance after a deliberate change to " + next);
                beginRescue();
                later(2, () -> {
                    assertRescueCrouch();
                    checkRescueStanceChanges(modes, index + 1, done);
                });
            });
        }

        void knock(int count) {
            victim.invulnerableTime = 0;
            victim.hurt(victim.damageSources().generic(), 1000);
            assertDowned(count);
        }

        void assertDowned(int count) {
            CombatPayloads.Snapshot data = snapshot();
            String observed = " [expectedCount=" + count + ", downed=" + CombatService.downed(victim)
                    + ", health=" + victim.getHealth() + ", forcedPose=" + victim.getForcedPose()
                    + ", pose=" + victim.getPose() + ", snapshotDowned=" + (data == null ? null : data.downed())
                    + ", snapshotCount=" + (data == null ? null : data.downCount())
                    + ", playerTicks=" + victim.tickCount + ", matchTicks=" + game.elapsedTicks()
                    + ", online=" + online(victim) + ", alive=" + victim.isAlive()
                    + ", rosterAlive=" + game.alive(victim.getUUID()) + "]";
            helper.assertTrue(online(victim) && victim.isAlive() && game.alive(victim.getUUID()),
                    "Carpet removed or eliminated a player whose lethal damage entered DBNO" + observed);
            // TaCZ clears the shared forcedPose slot when no gun is crawling. The synchronized
            // pose and physical dimensions are the DBNO contract enforced by our Player hook.
            helper.assertTrue(CombatService.downed(victim) && victim.getHealth() == 1
                    && victim.getPose() == Pose.SWIMMING && victim.getBoundingBox().getYsize() <= .61,
                    "Carpet continued its post-super death reset instead of preserving 1 HP and the downed pose" + observed);
            helper.assertTrue(data != null && data.downed() && data.downCount() == count,
                    "The authoritative snapshot lost the current or cumulative down state" + observed);
        }

        void measureLoss(boolean moving, DoubleConsumer result) {
            float before = snapshot().downedHealth();
            if (moving) victim.setPos(victim.position().add(.02, 0, 0));
            later(1, () -> result.accept(before - snapshot().downedHealth()));
        }

        void later(int ticks, CheckedAction action) { helper.runAfterDelay(ticks, () -> guard(action)); }
        void guard(CheckedAction action) {
            try { action.run(); }
            catch (Throwable failure) {
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                if (failure instanceof Error error) throw error;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(failure);
            }
        }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            CombatService.ended(server, game.session().sessionId());
            Method end = state.getClass().getDeclaredMethod("end", long.class);
            end.setAccessible(true);
            end.invoke(state, mapId);
            Method forget = state.getClass().getDeclaredMethod("forgetDeletedMap", long.class);
            forget.setAccessible(true);
            forget.invoke(state, mapId);
            for (var player : players) {
                if (online(player)) server.getPlayerList().remove(player);
                player.discard();
            }
        }
    }

    private static final class CaptureConnection extends NetHandlerPlayServerFake {
        CombatPayloads.Snapshot snapshot;
        final List<CombatPayloads.Event> events = new ArrayList<>();
        CaptureConnection(MinecraftServer server, ServerPlayer player, CommonListenerCookie cookie) {
            super(server, player, cookie);
        }
        @Override public void send(Packet<?> packet) { capture(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { capture(packet); }
        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof CombatPayloads.Snapshot value) snapshot = value;
                if (custom.payload() instanceof CombatPayloads.EventMessage value) events.add(value.event());
            }
        }
    }
}
