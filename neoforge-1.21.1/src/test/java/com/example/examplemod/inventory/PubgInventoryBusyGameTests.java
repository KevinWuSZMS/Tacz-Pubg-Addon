package com.example.examplemod.inventory;

import carpet.patches.NetHandlerPlayServerFake;
import com.example.examplemod.backpack.BackpackPayloads;
import com.example.examplemod.backpack.BackpackRegistration;
import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootWorldPhysics;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.example.examplemod.vitals.PubgMedicineItem;
import com.example.examplemod.vitals.PubgMedicineItems;
import com.example.examplemod.vitals.PubgMedicineService;
import com.example.examplemod.throwables.PubgThrowableEntity;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowableRegistration;
import com.example.examplemod.throwables.ThrowableService;
import com.example.examplemod.throwables.ThrowableSettingsService;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.match.MatchConfigModel;
import com.example.examplemod.zones.match.MatchEngine;
import com.example.examplemod.zones.match.MatchService;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.entity.sync.ModSyncedEntityData;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.example.examplemod.inventory.PubgInventoryPayloads.Action;

/** Real authorized command dispatch, including its snapshot, busy gate and transactional writes. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class PubgInventoryBusyGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(98_700_000);

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void weaponCardTransfersUseVisibleStorageOrLeaveSourceIntact(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var inventory = f.player.getInventory();
            var settings = InventoryConfig.current(f.player);
            int melee = settings.slot("melee"), throwable = settings.slot("throwable");
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            inventory.setItem(melee, sword);
            f.command(Action.MOVE, false, melee, -1);
            helper.assertTrue(inventory.getItem(melee).isEmpty() && ItemStack.matches(inventory.getItem(9), sword),
                    "Dragging the melee card to the list did not move it into storage");
            f.command(Action.MOVE, false, 9, melee);
            helper.assertTrue(ItemStack.matches(inventory.getItem(melee), sword) && inventory.getItem(9).isEmpty(),
                    "Dragging the melee item back to its matching card did not equip it");
            var grenade = ThrowableRegistration.ITEMS.get(ThrowableKind.FRAG).get();
            ItemStack pair = new ItemStack(grenade, 2);
            inventory.setItem(throwable, pair);
            for (int slot = 3; slot < 36; slot++) {
                if (settings.inPubgInventoryList(slot)) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            }
            f.command(Action.MOVE, false, throwable, -1);
            helper.assertTrue(inventory.getItem(throwable) == pair && pair.getCount() == 2 && f.groundCount(grenade) == 0,
                    "Dragging a throwable card into a full list changed its source or dropped items");
            inventory.setItem(9, ItemStack.EMPTY);
            f.command(Action.MOVE, false, throwable, -1);
            helper.assertTrue(inventory.getItem(throwable).isEmpty() && ItemStack.matches(inventory.getItem(9), pair),
                    "Dragging a throwable card to the list did not preserve its full stack");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void aimingCooldownAndReloadDoNotBlockUnrelatedHandleMoves(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:m4a1"))
                    .setAmmoCount(7).setAmmoInBarrel(true).build(f.player.registryAccess());
            f.player.getInventory().setItem(0, gun);
            f.player.getInventory().selected = 0;
            var data = IGunOperator.fromLivingEntity(f.player).getDataHolder();
            data.currentGunItem = () -> f.player.getMainHandItem();
            AtomicInteger refreshes = new AtomicInteger();
            Consumer<AttachmentPropertyEvent> listener = event -> refreshes.incrementAndGet();
            NeoForge.EVENT_BUS.addListener(listener);
            try {
                for (int mode = 0; mode < 3; mode++) {
                    data.isAiming = mode == 0;
                    ModSyncedEntityData.DRAW_COOL_DOWN_KEY.setValue(f.player, mode == 1 ? 5000L : 0L);
                    data.reloadTimestamp = mode == 2 ? System.currentTimeMillis() : -1;
                    long reload = data.reloadTimestamp;
                    helper.assertTrue(AutoAttachmentService.busy(f.player), "Fixture did not enter the requested real TaCZ busy state " + mode);
                    f.player.getInventory().setItem(9, new ItemStack(Items.STONE, 2));
                    f.command(Action.MOVE, false, 9, 10);
                    helper.assertTrue(f.player.getInventory().getItem(9).isEmpty() && f.player.getInventory().getItem(10).getCount() == 2,
                            "Real handle blocked unrelated storage movement for busy mode " + mode);
                    f.command(Action.DROP, false, 10, -1);
                    helper.assertTrue(f.player.getInventory().getItem(10).isEmpty() && f.groundCount(Items.STONE) == (mode + 1) * 2,
                            "Real handle blocked or duplicated an unrelated drop for busy mode " + mode);
                    helper.assertTrue(f.player.getMainHandItem() == gun && data.isAiming == (mode == 0)
                                    && data.reloadTimestamp == reload && IGunOperator.fromLivingEntity(f.player).getSynDrawCoolDown() == (mode == 1 ? 5000L : 0L),
                            "Unrelated inventory work canceled or replaced the active TaCZ state");
                }
                helper.assertTrue(refreshes.get() == 0, "Unchanged held gun received attachment-property refresh callbacks during ordinary sorting");
            } finally { NeoForge.EVENT_BUS.unregister(listener); }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void droppingTheActiveGunCommitsAndClearsItsNativeActions(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:m4a1"))
                    .setAmmoCount(7).setAmmoInBarrel(true).build(f.player.registryAccess());
            f.player.getInventory().setItem(0, gun); f.player.getInventory().selected = 0;
            var data = IGunOperator.fromLivingEntity(f.player).getDataHolder();
            data.currentGunItem = () -> f.player.getMainHandItem();
            data.reloadTimestamp = System.currentTimeMillis(); data.isBolting = true; data.chargeProgress = .5f;
            helper.assertTrue(AutoAttachmentService.busy(f.player), "Fixture lacks an active native gun action");
            f.command(Action.DROP, false, 0, -1);
            helper.assertTrue(f.player.getMainHandItem().isEmpty() && f.groundCount(gun.getItem()) == 1,
                    "Changing the active gun remained blocked until the native action completed");
            helper.assertTrue(data.reloadTimestamp < 0 && !data.reloadStateType.isReloading() && !data.isBolting && data.chargeProgress == 0,
                    "Committed active-gun drop retained a reload, bolt or charge against the removed gun");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void movingCompatibleReserveKeepsReloadButDroppingReserveCancelsIt(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack gun = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:m4a1"))
                    .setAmmoCount(7).setAmmoInBarrel(true).build(f.player.registryAccess());
            IGun api = (IGun) gun.getItem();
            var ammoId = TimelessAPI.getCommonGunIndex(api.getGunId(gun)).orElseThrow().getGunData().getAmmoId();
            ItemStack ammo = AmmoItemBuilder.create().setId(ammoId).setCount(4).build();
            f.player.getInventory().setItem(0, gun); f.player.getInventory().selected = 0;
            f.player.getInventory().setItem(9, ammo);
            var operator = IGunOperator.fromLivingEntity(f.player); var data = operator.getDataHolder();
            data.currentGunItem = () -> f.player.getMainHandItem();
            long reload = System.currentTimeMillis(); data.reloadTimestamp = reload;
            helper.assertTrue(operator.needCheckAmmo() && !api.useDummyAmmo(gun), "Fixture must reload from physical reserve ammunition");
            f.command(Action.MOVE, false, 9, 10);
            helper.assertTrue(f.player.getInventory().getItem(9).isEmpty() && f.player.getInventory().getItem(10).getCount() == 4
                            && f.player.getMainHandItem() == gun && data.reloadTimestamp == reload,
                    "Rearranging the unchanged compatible reserve canceled the native reload");
            f.command(Action.DROP, false, 10, -1);
            helper.assertTrue(f.player.getInventory().getItem(10).isEmpty() && f.groundCount(ammo.getItem()) == 4
                            && f.player.getMainHandItem() == gun && api.getCurrentAmmoCount(gun) == 7 && api.hasBulletInBarrel(gun)
                            && data.reloadTimestamp < 0 && !data.reloadStateType.isReloading(),
                    "Dropping compatible reserve was blocked, changed loaded rounds, or retained a stale native reload");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void medicineUseContinuesAcrossUnrelatedMovesAndDrops(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack medicine = f.beginMedicine(2);
            int remaining = f.player.getUseItemRemainingTicks();
            f.player.getInventory().setItem(9, new ItemStack(Items.STONE, 2));
            f.command(Action.MOVE, false, 9, 10);
            f.command(Action.DROP, false, 10, -1);
            PubgMedicineService.validateUse(f.player);
            helper.assertTrue(f.player.getInventory().getItem(9).isEmpty() && f.player.getInventory().getItem(10).isEmpty()
                            && f.groundCount(Items.STONE) == 2, "Using medicine still blocked real MOVE/DROP commands");
            f.assertMedicineContinues(medicine, remaining, 2);
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void collectingSameMedicinePreservesActiveObjectAndCountdown(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack medicine = f.beginMedicine(2);
            int remaining = f.player.getUseItemRemainingTicks();
            GroundLootEntity ground = f.ground(new ItemStack(PubgMedicineItems.BANDAGE.get(), 3));
            f.command(Action.MOVE, true, ground.getId(), -1);
            helper.assertTrue(ground.isRemoved(), "Real ground-to-inventory command did not collect the medicine");
            PubgMedicineService.validateUse(f.player);
            f.assertMedicineContinues(medicine, remaining, 5);
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void movingActiveMedicineCancelsUseWithoutConsumingOrDuplicatingIt(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack medicine = f.beginMedicine(2);
            f.command(Action.MOVE, false, 8, 9);
            helper.assertTrue(f.player.getInventory().getItem(8).isEmpty() && f.player.getInventory().getItem(9).getCount() == 2
                            && f.player.getInventory().getItem(9).is(PubgMedicineItems.BANDAGE.get()) && !f.player.isUsingItem(),
                    "Moving the active stack did not immediately cancel use and conserve its two physical items");
            ((PubgMedicineItem) medicine.getItem()).finishUsingItem(medicine, f.player.level(), f.player);
            helper.assertTrue(f.player.getHealth() == 5 && f.player.getInventory().getItem(9).getCount() == 2
                            && !PubgMedicineService.pendingContinuation(f.player),
                    "A stale completion healed or consumed a moved medicine stack after cancellation");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void postApplyFailureRestoresOriginalReferencesAndUseCountdown(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            ItemStack medicine = f.beginMedicine(2);
            int remaining = f.player.getUseItemRemainingTicks();
            ItemStack backpack = new ItemStack(BackpackRegistration.LEVEL_1.get());
            f.player.getInventory().setItem(9, backpack);
            var originalConnection = f.player.connection;
            var connection = new FailBackpackSync(f.player);
            f.player.connection = connection;
            try {
                // Tracking-and-self packets require a real ChunkMap entry, even for a FakePlayer.
                helper.getLevel().addNewPlayer(f.player);
                helper.assertTrue(helper.getLevel().getEntity(f.player.getId()) == f.player,
                        "Rollback fixture player did not enter the world's entity tracker");
                BackpackService.setEquipped(f.player, ItemStack.EMPTY);
                helper.assertTrue(connection.backpackPackets > 0,
                        "Unarmed backpack synchronization did not reach the tracked fixture connection");
                connection.armed = true;
                f.command(Action.MOVE, false, 9, 41);
                helper.assertTrue(connection.failed, "Fixture never reached the post-inventory-write backpack sync failure");
                helper.assertTrue(connection.observedAppliedInventory,
                        "Injected failure occurred before the source slot and independent backpack were written");
                helper.assertTrue(BackpackService.equipped(f.player).isEmpty() && f.player.getInventory().getItem(9) == backpack
                                && backpack.getCount() == 1, "Rollback replaced the original source object or lost the backpack");
                PubgMedicineService.validateUse(f.player);
                f.assertMedicineContinues(medicine, remaining, 2);
            } finally {
                // Remove tracking while the registered connection is still installed.
                f.player.discard();
                f.player.connection = originalConnection;
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void spareGrenadeDropPreviewNeverReleasesTheCookingGrenade(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var grenade = ThrowableRegistration.ITEMS.get(ThrowableKind.FRAG).get();
            ItemStack held = new ItemStack(grenade, 2), spare = new ItemStack(grenade);
            f.player.getInventory().selected = 4;
            f.player.getInventory().setItem(4, held); f.player.getInventory().setItem(9, spare);
            helper.assertTrue(ThrowableService.begin(f.player, grenade, InteractionHand.MAIN_HAND), "Unable to prepare real grenade use");
            f.player.startUsingItem(InteractionHand.MAIN_HAND);
            var game = MatchService.participantGame(f.player.server, f.player.getUUID());
            int preparation = ThrowableSettingsService.profile(ThrowableKind.FRAG).prepareTicks();
            for (int i = 0; i < preparation; i++) game.advance();
            ThrowableService.cook(f.player);
            helper.assertTrue(ThrowableService.cooking(f.player) && held.getCount() == 1, "Cooking fixture did not escrow exactly one grenade");
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof GroundLootEntity ground && ground.getItem().is(grenade)) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(veto);
            try { f.command(Action.DROP, false, 9, -1); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertTrue(ThrowableService.cooking(f.player) && f.player.isUsingItem() && f.player.getMainHandItem() == held
                            && f.player.getInventory().getItem(9) == spare && f.projectiles().isEmpty(),
                    "Canceled spare-stack drop preview released the escrowed grenade or canceled unrelated active use");
            f.command(Action.DROP, false, 9, -1);
            helper.assertTrue(f.player.getInventory().getItem(9).isEmpty() && f.groundCount(grenade) == 1
                            && ThrowableService.cooking(f.player) && f.player.getUseItem() == held && f.projectiles().isEmpty(),
                    "Successful spare-stack drop released the held grenade or duplicated the spare");
            ThrowableService.throwCooked(f.player);
            ThrowableService.throwCooked(f.player);
            helper.assertTrue(f.projectiles().size() == 1 && held.getCount() == 1 && f.groundCount(grenade) == 1,
                    "Cooking plus independent inventory drops did not conserve one projectile, one held spare and one ground spare");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void inventoryCanImmediatelyRecollectItsOwnDelayedFallingDrop(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            f.player.getInventory().setItem(9, new ItemStack(Items.STONE, 3));
            f.command(Action.DROP, false, 9, -1);
            var carriers = f.player.level().getEntitiesOfClass(GroundLootEntity.class, f.player.getBoundingBox().inflate(2),
                    ground -> ground.getItem().is(Items.STONE));
            helper.assertTrue(f.player.getInventory().getItem(9).isEmpty() && carriers.size() == 1
                            && carriers.getFirst().getQuantity() == 3, "Real DROP did not create exactly one three-item source");
            GroundLootEntity ground = carriers.getFirst();
            helper.assertTrue(!ground.canBePickedUpBy(f.player) && ground.canBePickedUpFromInventory(f.player)
                            && PubgInventoryService.nearby(f.player, ground),
                    "Fresh drop must be explicitly collectable in the inventory before native pickup delay/landing completes");
            // Deliberately do not tick the entity, countdown or world between these two commands.
            f.command(Action.MOVE, true, ground.getId(), -1);
            int stored = 0;
            for (int slot = 0; slot < 41; slot++) if (f.player.getInventory().getItem(slot).is(Items.STONE))
                stored += f.player.getInventory().getItem(slot).getCount();
            helper.assertTrue(ground.isRemoved() && f.groundCount(Items.STONE) == 0 && stored == 3,
                    "Immediate DROP-to-inventory collection was delayed or did not conserve all three physical items");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void immediateInventoryPickupStillRejectsOtherOwnersAndBlockedCarriers(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            GroundLootEntity ownedByAnother = f.ground(new ItemStack(Items.STONE, 2));
            ownedByAnother.initializeDrop(new ItemStack(Items.STONE, 2), f.player.blockPosition().below(), 10, UUID.randomUUID());
            helper.assertTrue(!ownedByAnother.canBePickedUpFromInventory(f.player), "Explicit pickup bypassed another player's ownership");
            f.command(Action.MOVE, true, ownedByAnother.getId(), -1);
            helper.assertTrue(!ownedByAnother.isRemoved() && ownedByAnother.getQuantity() == 2 && f.player.getInventory().isEmpty(),
                    "Real handle collected an owner-restricted source omitted from the nearby snapshot");

            GroundLootEntity blocked = f.ground(new ItemStack(Items.STONE, 3));
            blocked.setPhysicsState(GroundLootEntity.BLOCKED);
            helper.assertTrue(PubgInventoryService.nearby(f.player, blocked) && !blocked.canBePickedUpFromInventory(f.player),
                    "Fixture must distinguish a visible blocked carrier from an eligible explicit pickup");
            f.command(Action.MOVE, true, blocked.getId(), -1);
            helper.assertTrue(!blocked.isRemoved() && blocked.getQuantity() == 3 && f.player.getInventory().isEmpty()
                            && f.groundCount(Items.STONE) == 5,
                    "Explicit inventory pickup bypassed blocked physics or changed rejected source quantities");
            helper.succeed();
        }
    }

    private static final class FailBackpackSync extends NetHandlerPlayServerFake {
        final FakePlayer subject;
        boolean armed, failed, observedAppliedInventory;
        int backpackPackets;
        FailBackpackSync(FakePlayer player) {
            super(player.server, player, new CommonListenerCookie(player.getGameProfile(), 0, player.clientInformation(), false));
            subject = player;
        }
        private void inspect(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BackpackPayloads.State state
                    && state.owner().equals(subject.getUUID())) {
                backpackPackets++;
                if (armed) {
                    armed = false; failed = true;
                    observedAppliedInventory = subject.getInventory().getItem(9).isEmpty()
                            && BackpackService.equipped(subject).is(BackpackRegistration.LEVEL_1.get())
                            && state.equipment().is(BackpackRegistration.LEVEL_1.get());
                    throw new IllegalStateException("Deliberate one-shot post-apply sync failure");
                }
            }
        }
        @Override public void send(Packet<?> packet) { inspect(packet); }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { inspect(packet); }
    }

    /** Synchronous participant registration keeps tests on the actual handle gate without ticking a fake connection. */
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final FakePlayer player;
        final Object state;
        final long mapId = MAP_IDS.incrementAndGet();

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "inventory-busy"));
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
            player.setDeltaMovement(Vec3.ZERO); player.getInventory().clearContent();
            player.getAbilities().instabuild = false;
            var field = MatchService.class.getDeclaredField("STATES"); field.setAccessible(true);
            Map<MinecraftServer, Object> states = (Map<MinecraftServer, Object>) field.get(null);
            Class<?> type = Class.forName("com.example.examplemod.zones.match.MatchRuntimeState");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            state = states.containsKey(player.server) ? states.get(player.server) : constructor.newInstance();
            states.put(player.server, state);
            var map = new MapZoneModel.MapDefinition(mapId, "inventory busy fixture", player.level().dimension().location(),
                    origin.getX() - 50, origin.getZ() - 50, origin.getX() + 50, origin.getZ() + 50,
                    new MapZoneModel.Circle(origin.getX(), origin.getZ(), 40), 0, List.of());
            var defaults = MatchConfigModel.defaults(mapId);
            var settings = new MatchConfigModel.Settings(mapId, "inventory busy fixture", 1, 20, 1, 0, 2400, 60,
                    false, false, false, false, defaults.phases(),
                    new MatchConfigModel.BombingRules(false, .12, 60, defaults.bombingRules().projectile()),
                    defaults.flowRules(), false, false, new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52));
            var start = type.getDeclaredMethod("start", MapZoneModel.MapDefinition.class, MatchConfigModel.Settings.class, List.class, long.class);
            start.setAccessible(true);
            start.invoke(state, map, settings, List.of(new MatchEngine.Participant(player.getUUID(), "inventory-busy", "A")), 71L);
            helper.assertTrue(InventoryConfig.active(player), "Fixture did not activate the production inventory authorization gate");
        }

        ItemStack beginMedicine(int count) throws Exception {
            player.getInventory().selected = 8;
            player.setHealth(5);
            ItemStack stack = new ItemStack(PubgMedicineItems.BANDAGE.get(), count);
            player.getInventory().setItem(8, stack);
            PubgMedicineItems.BANDAGE.get().startTreatment(player.level(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(player.isUsingItem() && player.getUseItem() == stack, "Actual medicine service did not start the fixture cast");
            // Give the live native use a nonzero elapsed duration so restarting it cannot pass as continuity.
            var remaining = LivingEntity.class.getDeclaredField("useItemRemaining"); remaining.setAccessible(true);
            remaining.setInt(player, player.getUseItemRemainingTicks() - 7);
            return stack;
        }
        void assertMedicineContinues(ItemStack stack, int remaining, int count) {
            helper.assertTrue(player.isUsingItem() && player.getUseItem() == stack && player.getMainHandItem() == stack
                            && player.getUseItemRemainingTicks() == remaining && stack.getCount() == count && player.getHealth() == 5,
                    "Inventory work replaced the active medicine object, reset/canceled its countdown, or consumed/healed early");
        }
        @SuppressWarnings("unchecked")
        void command(Action action, boolean ground, int from, int to) throws Exception {
            map("LAST_REFRESH").remove(player.getUUID()); // Synchronous requests still obtain a real fresh server snapshot.
            PubgInventoryService.handle(player, new PubgInventoryPayloads.Command(Action.REFRESH, -1, false, -1, -1, ""));
            ArrayDeque<PubgInventoryPayloads.Snapshot> snapshots = (ArrayDeque<PubgInventoryPayloads.Snapshot>) map("SNAPSHOTS").get(player.getUUID());
            helper.assertTrue(snapshots != null && !snapshots.isEmpty(), "Real REFRESH did not authorize any command snapshot");
            PubgInventoryService.handle(player, new PubgInventoryPayloads.Command(action, snapshots.getLast().revision(), ground, from, to, ""));
        }
        GroundLootEntity ground(ItemStack stack) {
            BlockPos floor = player.blockPosition().below(); player.level().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            GroundLootEntity ground = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
            if (ground == null) throw new AssertionError("Ground entity type unavailable");
            ground.initializeDrop(stack, floor, 0, null);
            ground.setPos(player.getX(), floor.getY() + 1, player.getZ());
            helper.assertTrue(player.level().addFreshEntity(ground), "Ground fixture spawn was canceled");
            GroundLootWorldPhysics.prepareSpawn(ground); GroundLootWorldPhysics.tick(ground);
            helper.assertTrue(ground.canBePickedUpBy(player) && PubgInventoryService.nearby(player, ground), "Ground fixture is not visibly resting in range");
            return ground;
        }
        int groundCount(net.minecraft.world.item.Item item) {
            return player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2), e -> e.getItem().is(item))
                    .stream().mapToInt(GroundLootEntity::getQuantity).sum();
        }
        List<PubgThrowableEntity> projectiles() {
            return player.level().getEntitiesOfClass(PubgThrowableEntity.class, player.getBoundingBox().inflate(6), entity -> entity.getOwner() == player);
        }
        @Override public void close() throws Exception {
            PubgMedicineService.cancelUse(player); player.stopUsingItem();
            ThrowableService.cancel(player); projectiles().forEach(PubgThrowableEntity::discard);
            player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2)).forEach(GroundLootEntity::discard);
            BackpackService.setEquipped(player, ItemStack.EMPTY); player.getInventory().clearContent();
            for (String name : List.of("SNAPSHOTS", "RECENT", "LAST_REFRESH", "SESSIONS", "FAILURE")) map(name).remove(player.getUUID());
            var end = state.getClass().getDeclaredMethod("end", long.class); end.setAccessible(true); end.invoke(state, mapId);
            var forget = state.getClass().getDeclaredMethod("forgetDeletedMap", long.class); forget.setAccessible(true); forget.invoke(state, mapId);
            player.discard();
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> map(String name) throws Exception {
        var field = PubgInventoryService.class.getDeclaredField(name); field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }
}
