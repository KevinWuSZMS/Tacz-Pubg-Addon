package com.example.examplemod.zones.match;

import com.example.examplemod.backpack.BackpackMenu;
import com.example.examplemod.backpack.BackpackRegistration;
import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.backpack.BackpackWeights;
import com.example.examplemod.inventory.InventorySettings;
import com.example.examplemod.inventory.PubgInventoryService;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.example.examplemod.zones.MapZoneModel;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real registered items, active match capacity, menu swaps and canceled entity spawns. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class BackpackCapacityGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(9_200_000);

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void registeredWeightsEquipmentAndTemporaryMeleeSlotsAgree(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            for (Object[] sample : new Object[][] {
                    {"med_kit", 15_000}, {"c4", 35_000}, {"blue_zone_grenade", 23_000},
                    {"backpack_1", 400_000}, {"helmet_1", 40_000}, {"vest_1", 120_000}}) {
                ItemStack stack = item((String) sample[0]);
                helper.assertTrue(!stack.isEmpty() && BackpackWeights.unit(f.player, stack) == (int) sample[1],
                        "Wrong registered capacity: " + sample[0]);
            }
            helper.assertTrue(BackpackWeights.unit(f.player, new ItemStack(Items.ARROW)) == 2_000
                    && BackpackWeights.unit(f.player, new ItemStack(Items.CROSSBOW)) == 0,
                    "Vanilla crossbow adaptation disagrees with its equipped weapon role");
            BackpackService.setEquipped(f.player, item("backpack_3"));
            f.player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            f.player.setItemSlot(EquipmentSlot.HEAD, item("helmet_1"));
            f.player.setItemSlot(EquipmentSlot.CHEST, item("vest_1"));
            helper.assertTrue(BackpackService.used(f.player) == 0 && BackpackService.capacity(f.player) == 370_000,
                    "Worn gear consumed space or failed to add capacity");
            f.player.getInventory().setItem(3, new ItemStack(Items.IRON_SWORD));
            helper.assertTrue(BackpackService.used(f.player) == 0, "Equipped melee consumed spare capacity");
            ItemStack lrMelee = new ItemStack(BuiltInRegistries.ITEM.stream()
                    .filter(item -> item instanceof me.xjqsh.lrtactical.api.item.IMeleeWeapon).findFirst().orElseThrow());
            f.player.getInventory().setItem(3, lrMelee);
            helper.assertTrue(com.example.examplemod.inventory.MatchInventoryService.role(lrMelee).equals("melee")
                    && BackpackService.used(f.player) == 0, "Registered LR melee missed its equipment exemption");
            ItemStack medicine = item("med_kit");
            f.player.getInventory().setItem(3, medicine);
            helper.assertTrue(BackpackService.used(f.player) == 15_000,
                    "Medicine temporarily occupying the melee slot became weightless");
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(medicine.copy(), new ItemStack(Items.ARROW, 2))));
            f.player.getInventory().setItem(3, bundle);
            helper.assertTrue(BackpackService.used(f.player) == 20_000, "Nested contents bypassed capacity");
            f.settings(InventorySettings.defaults().withCapacity("item:tacz_pubg:med_kit", 7.));
            helper.assertTrue(BackpackWeights.unit(f.player, medicine) == 7_000 && BackpackService.used(f.player) == 12_000,
                    "Administrator override did not apply to nested or direct items");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void firstGroundPackAndUpgradeEquipWithoutStoringSparePack(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            f.player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
            f.player.getInventory().setItem(10, new ItemStack(Items.STONE, 6));
            GroundLootEntity first = f.ground(named(item("backpack_1"), "first pack"));
            first.pickup(f.player);
            helper.assertTrue(first.isRemoved() && BackpackService.tier(f.player) == 1
                    && BackpackService.used(f.player) == 70_000, "F could not equip a first pack with full base capacity");
            GroundLootEntity upgrade = f.ground(named(item("backpack_2"), "ground upgrade"));
            upgrade.pickup(f.player);
            helper.assertTrue(BackpackService.tier(f.player) == 2 && upgrade.getQuantity() == 1
                    && upgrade.getItem().getHoverName().getString().equals("first pack")
                    && BackpackService.used(f.player) == 70_000,
                    "F upgrade lost the displaced pack or charged its spare capacity");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void carriedPackSwapsDropExactlyOneOldPackAcrossMenus(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            for (String route : List.of("hand", "cursor", "shift", "inventory")) {
                f.player.getInventory().clearContent();
                f.player.inventoryMenu.setCarried(ItemStack.EMPTY);
                f.player.containerMenu = f.player.inventoryMenu;
                ItemStack old = named(item("backpack_1"), "old-" + route);
                ItemStack incoming = named(item("backpack_2"), "new-" + route);
                BackpackService.setEquipped(f.player, old);
                f.player.getInventory().setItem(9, new ItemStack(Items.STONE, 50));
                if (route.equals("hand")) {
                    f.player.getInventory().selected = 5;
                    f.player.setItemInHand(InteractionHand.MAIN_HAND, incoming);
                    BackpackService.equipHand(f.player, InteractionHand.MAIN_HAND);
                    helper.assertTrue(f.player.getMainHandItem().isEmpty(), "Hand source retained a consumed pack");
                } else if (route.equals("inventory")) {
                    f.player.getInventory().setItem(11, incoming);
                    BackpackService.equipInventory(f.player, 11);
                    helper.assertTrue(f.player.getInventory().getItem(11).isEmpty(), "Inventory source kept a consumed pack");
                } else {
                    BackpackMenu menu = new BackpackMenu(25, f.player.getInventory());
                    f.player.containerMenu = menu;
                    if (route.equals("cursor")) {
                        menu.setCarried(incoming);
                        menu.clicked(0, 0, ClickType.PICKUP, f.player);
                        helper.assertTrue(menu.getCarried().isEmpty(), "Backpack cursor kept a consumed pack");
                    } else {
                        f.player.getInventory().setItem(11, incoming);
                        menu.quickMoveStack(f.player, 3);
                        helper.assertTrue(f.player.getInventory().getItem(11).isEmpty(), "Shift source retained a consumed pack");
                    }
                }
                helper.assertTrue(ItemStack.matches(incoming, BackpackService.equipped(f.player))
                        && BackpackService.used(f.player) == 50_000, "Swap changed incoming components or left an overload: " + route);
                List<ItemEntity> drops = f.itemDrops(old);
                helper.assertTrue(drops.size() == 1 && ItemStack.matches(old, drops.getFirst().getItem()),
                        "Swap did not preserve exactly one old pack: " + route);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void canceledDropsDowngradesAndCustomPackWeightsConserveItems(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            ItemStack old = named(item("backpack_1"), "cancel original");
            ItemStack incoming = named(item("backpack_2"), "cancel replacement");
            BackpackService.setEquipped(f.player, old);
            f.player.setItemInHand(InteractionHand.MAIN_HAND, incoming);
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof ItemEntity item && ItemStack.matches(item.getItem(), old)) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(veto);
            try { BackpackService.equipHand(f.player, InteractionHand.MAIN_HAND); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertTrue(ItemStack.matches(old, BackpackService.equipped(f.player))
                    && ItemStack.matches(incoming, f.player.getMainHandItem()) && f.itemDrops(old).isEmpty(),
                    "Canceled drop changed or duplicated either pack");
            f.settings(InventorySettings.defaults().withCapacity("item:tacz_pubg:backpack_1", 10.));
            BackpackService.equipHand(f.player, InteractionHand.MAIN_HAND);
            helper.assertTrue(ItemStack.matches(incoming, BackpackService.equipped(f.player))
                    && ItemStack.matches(old, f.player.getMainHandItem()) && f.itemDrops(old).isEmpty()
                    && BackpackService.used(f.player) == 10_000,
                    "Custom small old-pack capacity should return the old item instead of dropping it");
            f.settings(InventorySettings.defaults());
            f.player.getInventory().clearContent();
            BackpackService.setEquipped(f.player, item("backpack_3"));
            f.player.setItemInHand(InteractionHand.MAIN_HAND, item("backpack_1"));
            for (int i = 9; i < 12; i++) f.player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            f.player.getInventory().setItem(12, new ItemStack(Items.STONE, 38));
            BackpackService.equipHand(f.player, InteractionHand.MAIN_HAND);
            helper.assertTrue(BackpackService.tier(f.player) == 3 && f.player.getMainHandItem().is(BackpackRegistration.LEVEL_1.get()),
                    "Downgrade left remaining contents above the smaller capacity");
            f.player.getInventory().clearContent();
            BackpackService.setEquipped(f.player, item("backpack_3"));
            BackpackMenu menu = new BackpackMenu(26, f.player.getInventory());
            f.player.containerMenu = menu;
            menu.clicked(0, 0, ClickType.QUICK_MOVE, f.player);
            helper.assertTrue(BackpackService.tier(f.player) == 3 && BackpackService.used(f.player) == 0,
                    "Removing a 400-space pack into a 70-space inventory bypassed capacity");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void pubgInventoryDropsCarriedArmorAndIndependentBackpack(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            for (int slot : new int[] {9, 39, 41}) {
                f.player.getInventory().clearContent();
                BackpackService.setEquipped(f.player, ItemStack.EMPTY);
                ItemStack original = named(slot == 9 ? new ItemStack(Items.STONE, 64)
                        : slot == 39 ? new ItemStack(Items.IRON_HELMET) : item("backpack_2"), "PUBG drop " + slot);
                if (slot == 41) BackpackService.setEquipped(f.player, original);
                else f.player.getInventory().setItem(slot, original);
                ItemStack unrelated = new ItemStack(Items.DIRT, 5);
                f.player.getInventory().setItem(10, unrelated.copy());
                helper.assertTrue(pubgDrop(f.player, slot), "PUBG drop rejected source slot " + slot);
                helper.assertTrue((slot == 41 ? BackpackService.equipped(f.player) : f.player.getInventory().getItem(slot)).isEmpty()
                                && ItemStack.matches(unrelated, f.player.getInventory().getItem(10)),
                        "PUBG drop retained its source or changed unrelated inventory at " + slot);
                var drops = f.itemDrops(original);
                helper.assertTrue(drops.size() == 1 && ItemStack.matches(original, drops.getFirst().getItem()),
                        "PUBG drop did not preserve one complete source stack at " + slot);
                ItemEntity dropped = drops.getFirst();
                helper.assertTrue(dropped.position().distanceToSqr(f.player.position().add(0, .2, 0)) < 1.0e-12
                                && dropped.getDeltaMovement().lengthSqr() == 0,
                        "PUBG inventory drop retained the forward throw trajectory at " + slot);
                var saved = new net.minecraft.nbt.CompoundTag();
                dropped.addAdditionalSaveData(saved);
                helper.assertTrue(saved.getShort("PickupDelay") > 0,
                        "Dropping at the feet erased the vanilla pickup delay at " + slot);
            }
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void pubgEquipmentDropsRespectRemainingCapacityAndSpawnCancellation(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            for (int slot : new int[] {38, 41}) {
                f.player.getInventory().clearContent();
                BackpackService.setEquipped(f.player, ItemStack.EMPTY);
                ItemStack original = named(slot == 38 ? new ItemStack(Items.IRON_CHESTPLATE) : item("backpack_3"),
                        "blocked PUBG drop " + slot);
                if (slot == 41) BackpackService.setEquipped(f.player, original);
                else f.player.getInventory().setItem(slot, original);
                f.player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
                f.player.getInventory().setItem(10, new ItemStack(Items.STONE, 16));
                helper.assertTrue(!pubgDrop(f.player, slot)
                                && ItemStack.matches(original, slot == 41 ? BackpackService.equipped(f.player) : f.player.getInventory().getItem(slot))
                                && BackpackService.used(f.player) == 80_000 && f.itemDrops(original).isEmpty(),
                        "Dropping equipment bypassed the smaller remaining capacity at " + slot);
                f.player.getInventory().setItem(10, ItemStack.EMPTY);
                Consumer<ItemTossEvent> tossVeto = event -> {
                    if (event.getPlayer() == f.player) event.setCanceled(true);
                };
                NeoForge.EVENT_BUS.addListener(tossVeto);
                try { helper.assertTrue(!pubgDrop(f.player, slot), "Canceled toss reported success"); }
                finally { NeoForge.EVENT_BUS.unregister(tossVeto); }
                Consumer<EntityJoinLevelEvent> spawnVeto = event -> {
                    if (event.getEntity() instanceof ItemEntity entity && ItemStack.matches(entity.getItem(), original)) event.setCanceled(true);
                };
                NeoForge.EVENT_BUS.addListener(spawnVeto);
                try { helper.assertTrue(!pubgDrop(f.player, slot), "Canceled entity spawn reported success"); }
                finally { NeoForge.EVENT_BUS.unregister(spawnVeto); }
                helper.assertTrue(ItemStack.matches(original, slot == 41 ? BackpackService.equipped(f.player) : f.player.getInventory().getItem(slot))
                                && BackpackService.used(f.player) == 64_000 && f.itemDrops(original).isEmpty(),
                        "Canceled equipment drop lost or duplicated the equipped item at " + slot);
            }
            helper.succeed();
        }
    }

    private static boolean pubgDrop(ServerPlayer player, int slot) throws Exception {
        var method = PubgInventoryService.class.getDeclaredMethod("drop", ServerPlayer.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, player, slot);
    }

    private static ItemStack item(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("tacz_pubg", id)));
    }
    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }
    private static final class Fixture implements AutoCloseable {
        final FakePlayer player;
        final MatchRuntimeState state;
        final long mapId = MAP_IDS.getAndIncrement();
        final List<Entity> drops = new ArrayList<>();
        final LootConfigStore store;
        final LootConfigModel.Document document;
        final java.lang.reflect.Field documentField;
        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper) throws Exception {
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[pack-capacity]"));
            BlockPos pos = helper.absolutePos(BlockPos.ZERO);
            player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
            player.getInventory().selected = 5;
            var field = MatchService.class.getDeclaredField("STATES");
            field.setAccessible(true);
            var states = (Map<MinecraftServer, MatchRuntimeState>) field.get(null);
            state = states.computeIfAbsent(helper.getLevel().getServer(), ignored -> new MatchRuntimeState());
            var map = new MapZoneModel.MapDefinition(mapId, "pack capacity fixture", helper.getLevel().dimension().location(),
                    pos.getX() - 50, pos.getZ() - 50, pos.getX() + 50, pos.getZ() + 50,
                    new MapZoneModel.Circle(pos.getX(), pos.getZ(), 40), 0, List.of());
            var defaults = MatchConfigModel.defaults(mapId);
            var settings = new MatchConfigModel.Settings(mapId, "pack capacity fixture", 1, 20, 1, 0, 2400, 60,
                    false, false, false, false, defaults.phases(), defaults.bombingRules(), defaults.flowRules(),
                    false, false, new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52), 1);
            state.start(map, settings, List.of(new MatchEngine.Participant(player.getUUID(), "fixture", "solo")), 19);
            store = LootConfigStore.get(helper.getLevel().getServer().overworld());
            document = store.document();
            documentField = LootConfigStore.class.getDeclaredField("document");
            documentField.setAccessible(true);
            settings(InventorySettings.defaults());
        }
        void settings(InventorySettings inventory) throws Exception {
            documentField.set(store, new LootConfigModel.Document(document.general(), document.items(),
                    document.gunSettings().withInventory(inventory), document.categoryProbabilities(), document.tierProfiles()));
        }
        List<ItemEntity> itemDrops(ItemStack expected) {
            var found = player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3),
                    item -> ItemStack.matches(item.getItem(), expected));
            drops.addAll(found);
            return found;
        }
        GroundLootEntity ground(ItemStack item) {
            GroundLootEntity entity = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
            if (entity == null) throw new AssertionError("Ground carrier registration missing");
            entity.initialize(item, 1, player.blockPosition().below(), 0);
            entity.setPos(player.getX(), player.getY(), player.getZ());
            entity.setPhysicsState(GroundLootEntity.RESTING);
            if (!player.level().addFreshEntity(entity)) throw new AssertionError("Ground fixture spawn failed");
            drops.add(entity);
            return entity;
        }
        @Override public void close() throws Exception {
            if (state.session(mapId) != null) state.end(mapId);
            state.forgetDeletedMap(mapId);
            documentField.set(store, document);
            drops.forEach(Entity::discard);
            player.getInventory().clearContent();
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
            player.discard();
        }
    }
}
