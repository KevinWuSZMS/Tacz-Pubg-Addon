package com.example.examplemod.vitals;

import carpet.patches.EntityPlayerMPFake;
import carpet.patches.NetHandlerPlayServerFake;
import com.mojang.authlib.GameProfile;
import com.example.examplemod.inventory.MatchInventoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real item use, five healing pulses and ordered health/forecast packets on an isolated server. */
@GameTestHolder("combat_dbno")
@PrefixGameTestTemplate(false)
public final class PubgMedicineGameTests {
    private static final AtomicInteger IDS = new AtomicInteger();

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void bandageUsesThreeSecondsThenFivePulsesOverTwoPointNineSeconds(GameTestHelper helper) throws Exception {
        new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 5, 60, 58, 2.4).run();
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void bandageStopsAtSeventyFivePercentWithoutBankingExcess(GameTestHelper helper) throws Exception {
        new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 14, 60, 58, 1).run();
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void firstAidUsesSixSecondsThenFivePulsesOverTwoSeconds(GameTestHelper helper) throws Exception {
        new Fixture(helper, PubgMedicineItems.FIRST_AID_KIT.get(), 7.6f, 120, 40, 15 - 7.6f).run();
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void medKitRestoresFullHealthImmediatelyAfterSixSeconds(GameTestHelper helper) throws Exception {
        new Fixture(helper, PubgMedicineItems.MED_KIT.get(), 3, 120, 0, 17).run();
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void bandageChainInterruptsAtCapWithoutConsumingNextBandage(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 14, 60, 58, 1);
        fixture.later(65, () -> {
            fixture.start(2);
            fixture.later(63, () -> {
                helper.assertTrue(fixture.completions.size() == 1 && fixture.player.isUsingItem(),
                        "Finishing one bandage below the cap did not immediately start the next");
                helper.assertTrue(fixture.capture.continued.size() == 1 && fixture.player.getMainHandItem().getCount() == 1,
                        "Continuation must acknowledge exactly one next cast without consuming it early");
            });
            fixture.later(123, () -> {
                fixture.near(fixture.player.getHealth(), 15, "The chain did not stop at seventy-five percent");
                helper.assertTrue(!fixture.player.isUsingItem() && fixture.completions.size() == 1
                                && fixture.player.getMainHandItem().getCount() == 1,
                        "The partially used second bandage was consumed or remained active at the cap");
                helper.assertTrue(fixture.capture.interrupted.size() == 1
                                && fixture.capture.interrupted.getFirst().clock == fixture.heals.getLast().clock,
                        "The interruption toast must be emitted on the exact healing tick that reaches the cap");
                fixture.player.setHealth(14);
                fixture.later(65, () -> {
                    helper.assertTrue(!fixture.player.isUsingItem() && fixture.completions.size() == 1
                                    && fixture.player.getMainHandItem().getCount() == 1,
                            "Taking later damage unexpectedly restarted the completed bandage chain");
                    fixture.close(); helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void bandageChainRefillsFromMainInventoryAndPreservesUnfinishedItem(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 12, 60, 58, 2.4);
        fixture.later(65, () -> {
            fixture.player.getInventory().setItem(9, new ItemStack(fixture.medicine, 2));
            fixture.start(1);
            fixture.later(63, () -> {
                helper.assertTrue(fixture.player.isUsingItem() && fixture.player.getMainHandItem().getItem() == fixture.medicine
                                && fixture.player.getMainHandItem().getCount() == 2 && fixture.player.getInventory().getItem(9).isEmpty(),
                        "An exhausted held stack did not refill from main inventory into the original hand");
                helper.assertTrue(fixture.capture.continued.size() == 1,
                        "Inventory refill must send one authoritative continuation to the client");
            });
            fixture.later(185, () -> {
                fixture.near(fixture.player.getHealth(), 15, "Inventory-backed bandage chain ended at the wrong health");
                helper.assertTrue(fixture.completions.size() == 2 && fixture.capture.continued.size() == 2,
                        "Exactly two bandages should finish before the third cast reaches the health cap");
                helper.assertTrue(!fixture.player.isUsingItem() && fixture.player.getMainHandItem().getCount() == 1
                                && fixture.player.getInventory().getItem(9).isEmpty(),
                        "Refill duplicated bandages or consumed the unfinished third one");
                helper.assertTrue(fixture.capture.interrupted.size() == 1,
                        "The inventory-backed chain did not emit one interruption at the cap");
                fixture.close(); helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void manuallyCancelledBandageChainNeverRestartsFromPendingRecovery(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 5, 60, 58, 2.4);
        fixture.later(65, () -> {
            fixture.start(3);
            fixture.later(65, () -> {
                helper.assertTrue(fixture.player.isUsingItem() && fixture.capture.continued.size() == 1,
                        "No second cast was active before cancelling the chain");
                // The cancel-key server handler releases the real use, invoking the item's cancellation callback.
                fixture.player.releaseUsingItem();
                helper.assertTrue(!fixture.player.isUsingItem(), "Manual cancellation did not release the next cast");
            });
            fixture.later(190, () -> {
                fixture.near(fixture.player.getHealth(), 7.4, "Cancellation should retain only the already completed healing budget");
                helper.assertTrue(!fixture.player.isUsingItem() && fixture.completions.size() == 1
                                && fixture.capture.continued.size() == 1 && fixture.player.getMainHandItem().getCount() == 2,
                        "Pending recovery resumed a cancelled chain or consumed another bandage");
                fixture.close(); helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 350)
    public static void changingSlotsCancelsBandageContinuationWithoutOverwritingTheNewItem(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 5, 60, 58, 2.4);
        fixture.later(65, () -> {
            fixture.start(3);
            int bandageSlot = fixture.player.getInventory().selected;
            int otherSlot = (bandageSlot + 1) % 9;
            fixture.player.getInventory().setItem(otherSlot, new ItemStack(net.minecraft.world.item.Items.STONE, 7));
            fixture.later(65, () -> fixture.player.getInventory().selected = otherSlot);
            fixture.later(70, () -> {
                helper.assertTrue(!fixture.player.isUsingItem()
                                && fixture.player.getMainHandItem().is(net.minecraft.world.item.Items.STONE)
                                && fixture.player.getMainHandItem().getCount() == 7,
                        "Slot switching did not cancel the chain or it overwrote the newly selected item");
                fixture.player.getInventory().selected = bandageSlot;
            });
            fixture.later(190, () -> {
                helper.assertTrue(!fixture.player.isUsingItem() && fixture.completions.size() == 1
                                && fixture.capture.continued.size() == 1 && fixture.player.getMainHandItem().getCount() == 2,
                        "Returning to the original slot unexpectedly resumed the cancelled chain");
                fixture.close(); helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 300)
    public static void temporaryHotbarSlotRestoresOnlyAfterTheLastBandage(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 5, 60, 58, 2.4);
        fixture.later(65, () -> {
            fixture.player.getInventory().selected = 5;
            fixture.start(2);
            var displaced = new ItemStack(net.minecraft.world.item.Items.STONE, 7);
            var temporary = new TemporarySlotHarness(fixture, 9, 5, 0, displaced);
            fixture.later(63, () -> {
                helper.assertTrue(temporary.pendingGaps == 1 && fixture.capture.continued.size() == 1
                                && fixture.player.isUsingItem() && fixture.player.getInventory().selected == 5,
                        "Restoring the temporary hotbar slot interrupted the next bandage");
                helper.assertTrue(fixture.player.getInventory().getItem(9) == displaced
                                && fixture.player.getMainHandItem().getCount() == 1,
                        "The displaced item moved or another bandage was consumed before its cast finished");
            });
            fixture.later(125, () -> {
                helper.assertTrue(temporary.pendingGaps == 2 && fixture.completions.size() == 2
                                && fixture.capture.continued.size() == 1 && !fixture.player.isUsingItem()
                                && !PubgMedicineService.pendingContinuation(fixture.player),
                        "Two completed bandages did not leave the continuation decision settled");
                helper.assertTrue(fixture.player.getInventory().selected == 0
                                && fixture.player.getInventory().getItem(5) == displaced
                                && fixture.player.getInventory().getItem(9).isEmpty() && !temporary.present(),
                        "Finishing the chain did not restore the exact displaced stack and previous selection");
                fixture.close(); helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "combat_dbno", batch = "combat_dbno", timeoutTicks = 300)
    public static void cancelledTemporaryBandageRestoresTheUnconsumedRemainder(GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, PubgMedicineItems.BANDAGE.get(), 5, 60, 58, 2.4);
        fixture.later(65, () -> {
            fixture.player.getInventory().selected = 5;
            fixture.start(3);
            var displaced = new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 11);
            var temporary = new TemporarySlotHarness(fixture, 9, 5, 0, displaced);
            fixture.later(65, () -> {
                helper.assertTrue(fixture.player.isUsingItem() && fixture.capture.continued.size() == 1,
                        "The temporary-slot chain never reached its second cast");
                fixture.player.releaseUsingItem();
            });
            fixture.later(135, () -> {
                helper.assertTrue(!fixture.player.isUsingItem() && !PubgMedicineService.pendingContinuation(fixture.player)
                                && fixture.completions.size() == 1 && fixture.capture.continued.size() == 1,
                        "A cancelled temporary-slot chain restarted or consumed another bandage");
                helper.assertTrue(fixture.player.getInventory().selected == 0 && !temporary.present()
                                && fixture.player.getInventory().getItem(5) == displaced
                                && fixture.player.getInventory().getItem(9).getItem() == fixture.medicine
                                && fixture.player.getInventory().getItem(9).getCount() == 2,
                        "Cancellation lost or duplicated the displaced item or unused bandages");
                fixture.close(); helper.succeed();
            });
        });
    }

    /** Reproduce the match router's PlayerTick.Post ordering without creating another match fixture. */
    private static final class TemporarySlotHarness {
        final Fixture fixture;
        final Map<UUID, Object> entries;
        final Consumer<PlayerTickEvent.Post> listener;
        int pendingGaps;
        @SuppressWarnings("unchecked")
        TemporarySlotHarness(Fixture fixture, int source, int target, int previousSelected, ItemStack displaced) {
            this.fixture = fixture;
            try {
                var field = MatchInventoryService.class.getDeclaredField("TEMPORARY");
                field.setAccessible(true);
                entries = (Map<UUID, Object>) field.get(null);
                Class<?> operation = Class.forName(MatchInventoryService.class.getName() + "$TemporaryUse");
                var constructor = operation.getDeclaredConstructor(int.class, int.class, int.class, ItemStack.class);
                constructor.setAccessible(true);
                entries.put(fixture.player.getUUID(), constructor.newInstance(source, target, previousSelected, displaced));
                fixture.player.getInventory().setItem(source, displaced);
                var restore = MatchInventoryService.class.getDeclaredMethod("restoreTemporary", ServerPlayer.class);
                restore.setAccessible(true);
                listener = event -> {
                    if (event.getEntity() != fixture.player) return;
                    boolean gap = !fixture.player.isUsingItem() && PubgMedicineService.pendingContinuation(fixture.player);
                    if (gap) pendingGaps++;
                    try { restore.invoke(null, fixture.player); }
                    catch (ReflectiveOperationException e) { throw new AssertionError("Cannot invoke temporary inventory restoration", e); }
                    if (gap) fixture.helper.assertTrue(fixture.player.getInventory().selected == target
                                    && fixture.player.getInventory().getItem(source) == displaced && present(),
                            "PlayerTick.Post restored a borrowed slot before ServerTick.Post decided bandage continuation");
                };
                NeoForge.EVENT_BUS.addListener(listener);
                fixture.extraCleanup.add(this::close);
            } catch (ReflectiveOperationException e) { throw new AssertionError("Cannot configure temporary-slot regression", e); }
        }
        boolean present() { return entries.containsKey(fixture.player.getUUID()); }
        void close() { NeoForge.EVENT_BUS.unregister(listener); entries.remove(fixture.player.getUUID()); }
    }

    private record Heal(long clock, float amount, float before, int usingTicks) {}
    private record Finished(long clock, int usingTicks, int observedTicks, int lastRemaining) {}
    private record Forecast(long clock, MatchVitalsPayloads.RecoveryState state, float health, long healthClock) {}
    private record Continued(long clock, MatchVitalsPayloads.BandageContinued packet) {}
    private record Interrupted(long clock, MatchVitalsPayloads.MedicineInterrupted packet) {}

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final EntityPlayerMPFake player;
        final PubgMedicineItem medicine;
        final float initialHealth;
        final int useTicks, recoveryTicks;
        final double budget;
        final Map<MinecraftServer, Long> clocks;
        final CaptureConnection capture;
        final List<Heal> heals = new ArrayList<>();
        final List<Finished> completions = new ArrayList<>();
        final List<Runnable> extraCleanup = new ArrayList<>();
        final Consumer<LivingHealEvent> healListener;
        final Consumer<LivingEntityUseItemEvent.Tick> useTickListener;
        final Consumer<LivingEntityUseItemEvent.Finish> finishListener;
        boolean observing, closed;
        int observedUseTicks, lastUseRemaining;
        long started;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper, PubgMedicineItem medicine, float health,
                int useTicks, int recoveryTicks, double budget) throws Exception {
            this.helper = helper; this.medicine = medicine; this.initialHealth = health;
            this.useTicks = useTicks; this.recoveryTicks = recoveryTicks; this.budget = budget;
            server = helper.getLevel().getServer();
            var clockField = PubgMedicineService.class.getDeclaredField("ORDINARY_CLOCKS");
            clockField.setAccessible(true);
            clocks = (Map<MinecraftServer, Long>) clockField.get(null);
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            var position = new Vec3(origin.getX() + 2.5, origin.getY() + 2, origin.getZ() + 2.5);
            player = EntityPlayerMPFake.respawnFake(helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "medicine" + IDS.incrementAndGet()), ClientInformation.createDefault());
            player.fixStartingPosition = () -> player.moveTo(position.x, position.y, position.z, 0, 0);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, player.clientInformation(), false);
            server.getPlayerList().placeNewPlayer(NetHandlerPlayServerFake.DUMMY_CONNECTION, player, cookie);
            capture = new CaptureConnection(server, player, cookie, this);
            player.connection = capture;
            player.teleportTo(helper.getLevel(), position.x, position.y, position.z, 0, 0);
            player.setGameMode(GameType.ADVENTURE);
            player.setNoGravity(true); player.setDeltaMovement(Vec3.ZERO);
            player.getAbilities().flying = false;
            player.getFoodData().setFoodLevel(10); player.getFoodData().setSaturation(0);
            healListener = event -> {
                if (observing && event.getEntity() == player)
                    heals.add(new Heal(clock(), event.getAmount(), player.getHealth(), player.getTicksUsingItem()));
            };
            useTickListener = event -> {
                if (observing && event.getEntity() == player) {
                    observedUseTicks++;
                    lastUseRemaining = event.getDuration();
                }
            };
            finishListener = event -> {
                if (observing && event.getEntity() == player) {
                    // Finish fires after consumption, when the last-item live stack is already empty.
                    // Its pre-consumption copy and remaining duration still identify the full cast.
                    completions.add(new Finished(clock(), event.getItem().getUseDuration(player) - event.getDuration(),
                            observedUseTicks, lastUseRemaining));
                    observedUseTicks = 0;
                }
            };
            NeoForge.EVENT_BUS.addListener(healListener);
            NeoForge.EVENT_BUS.addListener(useTickListener);
            NeoForge.EVENT_BUS.addListener(finishListener);
        }

        long clock() { return clocks.getOrDefault(server, 0L); }

        void start(int count) {
            player.setHealth(initialHealth);
            player.getFoodData().setFoodLevel(10); player.getFoodData().setSaturation(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(medicine, count));
            helper.assertTrue(medicine.getUseDuration(player.getMainHandItem(), player) == useTicks,
                    "Server item duration does not match the requested PUBG 42.1 cast time");
            started = clock(); observing = true;
            PubgMedicineService.startHeld(player, player.getInventory().selected, 1);
            helper.assertTrue(player.isUsingItem() && capture.started != null && capture.started.duration() == useTicks,
                    "Real medicine request failed or acknowledged the wrong duration");
        }

        int baselineCount() { return medicine.kind() == PubgMedicineModel.Kind.BANDAGE ? 1 : 2; }

        void run() {
            later(65, () -> {
                start(baselineCount());
                later(useTicks - 2, () -> {
                    helper.assertTrue(player.isUsingItem() && player.getMainHandItem().getCount() == baselineCount(),
                            "Medicine finished or consumed before its full cast duration");
                    near(player.getHealth(), initialHealth, "Medicine healed during the cast");
                });
                later(useTicks + recoveryTicks + 5, () -> {
                    verify(); close(); helper.succeed();
                });
            });
        }

        void verify() {
            helper.assertTrue(completions.size() == 1, "Expected one native item-use completion, got " + completions.size());
            var finish = completions.getFirst();
            helper.assertTrue(finish.usingTicks == useTicks,
                    "Native use finished without all entity ticks: " + finish.usingTicks + " / " + useTicks);
            helper.assertTrue(finish.observedTicks == useTicks && finish.lastRemaining == 1,
                    "Native use must execute every countdown tick through the last remaining tick: "
                            + finish.observedTicks + " observed, " + finish.lastRemaining + " remaining");
            // GameTestTicker starts this cast after entity ticking but before ServerTickEvent.Post;
            // that first post tick advances the clock before this player's first item-use tick.
            helper.assertTrue(finish.clock - started == useTicks,
                    "Server cast clock finished at " + (finish.clock - started) + " ticks instead of " + useTicks);
            helper.assertTrue(!player.isUsingItem() && player.getMainHandItem().getCount() == baselineCount() - 1,
                    "Completed medicine did not consume exactly one item");
            helper.assertTrue(capture.continued.isEmpty(),
                    "Exhausted bandages, first aid and med kits must not create a continuation");
            near(player.getHealth(), initialHealth + budget, "Treatment ended at the wrong Minecraft health");
            helper.assertTrue(heals.size() == (recoveryTicks == 0 ? 1 : 5),
                    "Expected discrete healing pulses; observed " + heals.size());
            if (recoveryTicks == 0) {
                var heal = heals.getFirst();
                helper.assertTrue(heal.clock == finish.clock && heal.usingTicks == useTicks,
                        "Med kit healing was delayed instead of occurring in the final use tick");
                near(heal.amount, budget, "Med kit did not immediately fill the missing health");
                helper.assertTrue(capture.forecasts.stream().noneMatch(frame -> frame.state.remaining() > 0),
                        "Instant med kit advertised a delayed recovery budget");
                return;
            }
            var firstForecast = capture.forecasts.stream().filter(frame -> frame.state.remaining() > 0).findFirst().orElseThrow();
            helper.assertTrue(firstForecast.clock == finish.clock && firstForecast.state.remainingTicks() == recoveryTicks,
                    "Recovery did not start at native cast completion with the full configured duration");
            near(firstForecast.state.remaining(), budget, "Initial HUD forecast lost the fixed treatment budget");
            near(firstForecast.state.next(), budget / 5, "Initial HUD next-step forecast did not show one fifth");
            for (int index = 0; index < 5; index++) {
                int pulse = index + 1;
                var heal = heals.get(index);
                long expected = (recoveryTicks * pulse + 4L) / 5;
                helper.assertTrue(heal.clock - finish.clock == expected,
                        "Pulse " + pulse + " arrived at " + (heal.clock - finish.clock) + " ticks instead of " + expected);
                near(heal.amount, budget / 5, "Pulse " + pulse + " did not receive one equal share");
                near(heal.before, initialHealth + budget * index / 5, "Health changed outside the scheduled pulses");
                double remaining = budget * (5 - pulse) / 5;
                var forecast = capture.forecasts.stream()
                        .filter(frame -> frame.clock == heal.clock && Math.abs(frame.state.remaining() - remaining) < .001)
                        .findFirst().orElseThrow(() -> new AssertionError("Pulse " + pulse + " did not immediately update HUD remaining recovery"));
                near(forecast.state.next(), Math.min(remaining, budget / 5), "HUD next pulse disagreed with the server treatment");
                near(forecast.health, initialHealth + budget * pulse / 5, "HUD forecast arrived before its matching health packet");
                helper.assertTrue(forecast.healthClock == forecast.clock, "Health and recovery were published on different pulse ticks");
            }
            helper.assertTrue(capture.forecasts.getLast().state.remaining() == 0,
                    "Completed recovery left a stale healing forecast");
        }

        void near(double actual, double expected, String message) {
            helper.assertTrue(Math.abs(actual - expected) < .001, message + ": " + actual + " instead of " + expected);
        }

        void later(int ticks, Runnable action) {
            helper.runAfterDelay(ticks, () -> {
                try { action.run(); }
                catch (Throwable failure) {
                    close();
                    if (failure instanceof Error error) throw error;
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    throw new RuntimeException(failure);
                }
            });
        }

        @Override public void close() {
            if (closed) return;
            closed = true; observing = false;
            extraCleanup.forEach(Runnable::run);
            NeoForge.EVENT_BUS.unregister(healListener); NeoForge.EVENT_BUS.unregister(useTickListener);
            NeoForge.EVENT_BUS.unregister(finishListener);
            player.stopUsingItem(); PubgMedicineService.cancelUse(player);
            if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
            player.discard();
        }
    }

    private static final class CaptureConnection extends NetHandlerPlayServerFake {
        final Fixture fixture;
        final List<Forecast> forecasts = new ArrayList<>();
        final List<Continued> continued = new ArrayList<>();
        final List<Interrupted> interrupted = new ArrayList<>();
        MatchVitalsPayloads.MedicineStarted started;
        float health;
        long healthClock = -1;
        CaptureConnection(MinecraftServer server, ServerPlayer player, CommonListenerCookie cookie, Fixture fixture) {
            super(server, player, cookie); this.fixture = fixture;
        }
        @Override public void send(Packet<?> packet) { capture(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { capture(packet); }
        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSetHealthPacket value) { health = value.getHealth(); healthClock = fixture.clock(); }
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof MatchVitalsPayloads.MedicineStarted value) started = value;
                if (fixture.observing && custom.payload() instanceof MatchVitalsPayloads.RecoveryState value)
                    forecasts.add(new Forecast(fixture.clock(), value, health, healthClock));
                if (fixture.observing && custom.payload() instanceof MatchVitalsPayloads.BandageContinued value)
                    continued.add(new Continued(fixture.clock(), value));
                if (fixture.observing && custom.payload() instanceof MatchVitalsPayloads.MedicineInterrupted value)
                    interrupted.add(new Interrupted(fixture.clock(), value));
            }
        }
    }
}
