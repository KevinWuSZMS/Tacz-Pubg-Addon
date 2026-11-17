package com.example.examplemod.zones.match;

import com.example.examplemod.armor.ArmorRegistration;
import com.example.examplemod.backpack.BackpackRegistration;
import com.example.examplemod.inventory.EquipmentInventoryRules;
import com.example.examplemod.inventory.EquipmentPreference;
import com.example.examplemod.inventory.InventorySettings;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real equipment components, menu mixins, ground carriers and cancellation in the isolated test save. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class EquipmentPickupGameTests {
    private static final AtomicLong MAP_IDS = new AtomicLong(9_100_000);

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void equipmentSwapsAndAllAcquisitionPathsRespectCategoryLimit(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper, 1, false)) {
            var player = f.player;
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            ItemStack old = named(new ItemStack(ArmorRegistration.HELMET_1.get()), "damaged original");
            old.setDamageValue(123);
            player.setItemSlot(EquipmentSlot.HEAD, old.copy());
            ItemStack incoming = named(new ItemStack(ArmorRegistration.HELMET_2.get()), "replacement");
            GroundLootEntity source = f.ground(incoming, 1);
            helper.assertTrue(source.exchangeEquipment(player, false), "F equipment replacement failed with a full inventory");
            helper.assertTrue(ItemStack.matches(player.getItemBySlot(EquipmentSlot.HEAD), incoming)
                    && source.getQuantity() == 1 && ItemStack.matches(source.getItem(), old),
                    "Single-carrier swap changed count, original damage, or components");
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
            ItemStack extra = new ItemStack(ArmorRegistration.HELMET_3.get());
            helper.assertTrue(!player.getInventory().add(extra) && extra.getCount() == 1,
                    "An empty inventory accepted a second helmet despite an already worn helmet of a different tier");
            ItemStack otherCategory = new ItemStack(BackpackRegistration.LEVEL_1.get());
            helper.assertTrue(EquipmentInventoryRules.allowed(player, otherCategory) == 1,
                    "An equipped helmet incorrectly blocked the separate backpack category");
            GroundLootEntity firstPack = f.ground(otherCategory, 1);
            firstPack.pickup(player);
            helper.assertTrue(firstPack.isRemoved()
                    && player.getItemBySlot(EquipmentSlot.LEGS).is(BackpackRegistration.LEVEL_1.get())
                    && ItemStack.matches(player.getItemBySlot(EquipmentSlot.HEAD), incoming),
                    "Picking up the first pack failed to equip it alongside the worn helmet");
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);

            var chestItems = new SimpleContainer(9);
            chestItems.setItem(0, extra.copy());
            var menu = new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1,
                    33, player.getInventory(), chestItems, 1);
            player.containerMenu = menu;
            for (ClickType click : new ClickType[]{ClickType.PICKUP, ClickType.QUICK_MOVE, ClickType.SWAP}) {
                menu.clicked(0, click == ClickType.SWAP ? 5 : 0, click, player);
                helper.assertTrue(chestItems.getItem(0).getCount() == 1 && menu.getCarried().isEmpty(),
                        "External " + click + " bypassed the worn-category count");
            }
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            menu.setCarried(incoming.copy());
            helper.assertTrue(EquipmentInventoryRules.allowed(player, extra) == 0,
                    "Cursor-held helmet did not count toward the same category");
            menu.setCarried(ItemStack.EMPTY);
            player.inventoryMenu.getSlot(1).set(incoming.copy());
            helper.assertTrue(EquipmentInventoryRules.allowed(player, extra) == 0,
                    "Hidden personal crafting input bypassed equipment count");
            player.inventoryMenu.getSlot(1).set(ItemStack.EMPTY);
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(incoming.copy())));
            player.getInventory().setItem(9, bundle);
            helper.assertTrue(EquipmentInventoryRules.allowed(player, extra) == 0,
                    "Nested equipment bypassed the category count");
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(BackpackRegistration.LEVEL_3.get()));
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
            for (int i = 9; i < 13; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            player.getInventory().setItem(13, new ItemStack(Items.STONE, 33));
            ItemStack fullWeightBundle = new ItemStack(Items.BUNDLE);
            fullWeightBundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.DIAMOND, 30))));
            menu.setCarried(fullWeightBundle);
            chestItems.setItem(0, new ItemStack(Items.DIAMOND));
            helper.assertTrue(com.example.examplemod.backpack.BackpackService.used(player) == 320_000
                    && !com.example.examplemod.backpack.BackpackService.permitsClick(menu, 0, 1, ClickType.PICKUP, player),
                    "A cursor bundle received credit for departing while actually absorbing an external item");
            menu.setCarried(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            GroundLootEntity smaller = f.ground(new ItemStack(BackpackRegistration.LEVEL_1.get()), 1);
            helper.assertTrue(!smaller.exchangeEquipment(player, false)
                    && player.getItemBySlot(EquipmentSlot.LEGS).is(BackpackRegistration.LEVEL_3.get())
                    && smaller.getQuantity() == 1, "Backpack downgrade swallowed an over-capacity inventory");
            f.state.end(f.mapId);
            helper.assertTrue(EquipmentInventoryRules.allowed(player, extra) == 1,
                    "Equipment restriction remained active after the match ended");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void automaticUpgradesAndSpawnCancellationConserveEquipment(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper, 1, true)) {
            var player = f.player;
            ItemStack original = named(new ItemStack(ArmorRegistration.VEST_2.get()), "original vest");
            player.setItemSlot(EquipmentSlot.CHEST, original.copy());
            ItemStack damaged = new ItemStack(ArmorRegistration.VEST_3.get());
            damaged.setDamageValue(damaged.getMaxDamage() - 100);
            helper.assertTrue(!player.getInventory().add(damaged) && damaged.getCount() == 1
                    && ItemStack.matches(original, player.getItemBySlot(EquipmentSlot.CHEST)),
                    "One-durability tier-three vest replaced an intact tier-two vest");
            ItemStack upgrade = new ItemStack(ArmorRegistration.VEST_3.get());
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof GroundLootEntity) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(veto);
            try {
                helper.assertTrue(!player.getInventory().add(upgrade) && upgrade.getCount() == 1
                        && ItemStack.matches(original, player.getItemBySlot(EquipmentSlot.CHEST)),
                        "Canceled old-equipment spawn consumed either side of the automatic swap");
            } finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertTrue(player.getInventory().add(upgrade) && upgrade.isEmpty()
                    && player.getItemBySlot(EquipmentSlot.CHEST).is(ArmorRegistration.VEST_3.get()),
                    "Better vest was rejected by the category limit before automatic equip");
            var drops = helper.getLevel().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(3));
            long originals = drops.stream().filter(e -> ItemStack.matches(e.getItem(), original))
                    .mapToLong(GroundLootEntity::getQuantity).sum();
            helper.assertTrue(originals == 1, "Automatic upgrade lost or duplicated the displaced real vest");
            f.drops.addAll(drops);
            player.getItemBySlot(EquipmentSlot.CHEST).setDamageValue(2000);
            ItemStack shortcutOld = player.getItemBySlot(EquipmentSlot.CHEST).copy();
            var chestItems = new SimpleContainer(9);
            chestItems.setItem(0, new ItemStack(ArmorRegistration.VEST_3.get()));
            var menu = new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1,
                    34, player.getInventory(), chestItems, 1);
            player.containerMenu = menu;
            player.getInventory().setItem(5, new ItemStack(Items.STICK, 3));
            menu.clicked(0, 5, ClickType.SWAP, player);
            helper.assertTrue(chestItems.getItem(0).isEmpty()
                    && player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() == 0
                    && player.getInventory().getItem(5).is(Items.STICK)
                    && player.getInventory().getItem(5).getCount() == 3,
                    "Number-key acquisition skipped automatic upgrade or displaced unrelated hotbar items");
            var shortcutDrops = helper.getLevel().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(3));
            helper.assertTrue(shortcutDrops.stream().filter(e -> ItemStack.matches(e.getItem(), shortcutOld))
                    .mapToLong(GroundLootEntity::getQuantity).sum() == 1,
                    "Number-key upgrade duplicated or deleted the displaced vest");
            f.drops.addAll(shortcutDrops);
            helper.assertTrue(!EquipmentPreference.betterArmor(false, 3, 1, 2, 220)
                    && EquipmentPreference.betterArmor(false, 2, 220, 3, 1)
                    && !EquipmentPreference.betterArmor(true, 3, 0, 2, 150)
                    && EquipmentPreference.betterArmor(true, 3, 1, 2, 150)
                    && !EquipmentPreference.betterArmor(false, 2, 100.5, 2, 100)
                    && EquipmentPreference.betterArmor(false, 2, 101, 2, 100),
                    "Armor preference violated researched protection or same-tier durability thresholds");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void deliberateGunSwapAndMultiItemEquipmentExchangePreserveSources(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper, 0, false)) {
            var player = f.player;
            ItemStack old = named(new ItemStack(ArmorRegistration.HELMET_1.get()), "old helmet");
            old.setDamageValue(270);
            player.setItemSlot(EquipmentSlot.HEAD, old.copy());
            ItemStack incoming = new ItemStack(ArmorRegistration.HELMET_2.get());
            GroundLootEntity source = f.ground(incoming, 3);
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof GroundLootEntity && event.getEntity() != source) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(veto);
            try {
                helper.assertTrue(!source.exchangeEquipment(player, false) && source.getQuantity() == 3
                        && ItemStack.matches(old, player.getItemBySlot(EquipmentSlot.HEAD)),
                        "Failed multi-item source exchange changed the source or worn item");
            } finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertTrue(source.exchangeEquipment(player, false) && source.getQuantity() == 2
                    && ItemStack.matches(incoming, player.getItemBySlot(EquipmentSlot.HEAD)),
                    "Unlimited spare rule stopped explicit F from equipping exactly one item");
            ItemStack first = named(new ItemStack(Items.CROSSBOW), "first gun");
            ItemStack second = named(new ItemStack(Items.CROSSBOW), "selected gun");
            second.setDamageValue(31);
            player.getInventory().setItem(0, first.copy());
            player.getInventory().setItem(1, second.copy());
            ItemStack next = named(new ItemStack(Items.CROSSBOW), "new gun");
            GroundLootEntity gun = f.ground(next, 1);
            player.getInventory().selected = 5;
            gun.pickup(player);
            helper.assertTrue(ItemStack.matches(player.getInventory().getItem(0), first)
                    && ItemStack.matches(player.getInventory().getItem(1), second)
                    && ItemStack.matches(gun.getItem(), next), "F replaced an unselected gun");
            player.getInventory().selected = 1;
            gun.pickup(player);
            helper.assertTrue(ItemStack.matches(player.getInventory().getItem(0), first)
                    && ItemStack.matches(player.getInventory().getItem(1), next)
                    && gun.getQuantity() == 1 && ItemStack.matches(gun.getItem(), second),
                    "Selected gun swap changed a second slot or lost exact source components");
            var chestItems = new SimpleContainer(9);
            chestItems.setItem(0, second.copy());
            var menu = new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1,
                    35, player.getInventory(), chestItems, 1);
            player.containerMenu = menu;
            player.getInventory().setItem(5, new ItemStack(Items.STICK, 3));
            menu.clicked(0, 5, ClickType.SWAP, player);
            helper.assertTrue(ItemStack.matches(chestItems.getItem(0), second)
                    && player.getInventory().getItem(5).is(Items.STICK),
                    "Number-key swap acquired a third primary gun into a free hotbar slot");
            player.getInventory().setItem(1, ItemStack.EMPTY);
            menu.clicked(0, 5, ClickType.SWAP, player);
            helper.assertTrue(chestItems.getItem(0).isEmpty()
                    && ItemStack.matches(player.getInventory().getItem(1), second)
                    && player.getInventory().getItem(5).is(Items.STICK)
                    && player.getInventory().getItem(5).getCount() == 3,
                    "Number-key gun acquisition failed to use the empty fixed slot without swapping unrelated items");
            f.drops.addAll(helper.getLevel().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(3)));
            helper.succeed();
        }
    }

    private static ItemStack named(ItemStack stack, String name) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }
    private static final class Fixture implements AutoCloseable {
        final FakePlayer player;
        final MatchRuntimeState state;
        final long mapId = MAP_IDS.getAndIncrement();
        final List<GroundLootEntity> drops = new ArrayList<>();
        final LootConfigStore store;
        final LootConfigModel.Document document;
        final java.lang.reflect.Field documentField;
        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper, int limit, boolean automatic) throws Exception {
            player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[equipment]"));
            BlockPos pos = helper.absolutePos(BlockPos.ZERO);
            player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
            player.getInventory().selected = 5;
            var field = MatchService.class.getDeclaredField("STATES");
            field.setAccessible(true);
            var states = (Map<MinecraftServer, MatchRuntimeState>) field.get(null);
            state = states.computeIfAbsent(helper.getLevel().getServer(), ignored -> new MatchRuntimeState());
            var map = new MapZoneModel.MapDefinition(mapId, "equipment fixture", helper.getLevel().dimension().location(),
                    pos.getX() - 50, pos.getZ() - 50, pos.getX() + 50, pos.getZ() + 50,
                    new MapZoneModel.Circle(pos.getX(), pos.getZ(), 40), 0, List.of());
            var defaults = MatchConfigModel.defaults(mapId);
            var settings = new MatchConfigModel.Settings(mapId, "equipment fixture", 1, 20, 1, 0, 2400, 60,
                    false, false, false, false, defaults.phases(), defaults.bombingRules(), defaults.flowRules(),
                    false, false, new MatchConfigModel.FlightRules(false, true, 40, 512, 3, 52), limit);
            state.start(map, settings, List.of(new MatchEngine.Participant(player.getUUID(), "fixture", "solo")), 19);
            store = LootConfigStore.get(helper.getLevel().getServer().overworld());
            document = store.document();
            documentField = LootConfigStore.class.getDeclaredField("document");
            documentField.setAccessible(true);
            documentField.set(store, new LootConfigModel.Document(document.general(), document.items(),
                    document.gunSettings().withInventory(InventorySettings.defaults().withAutoEquipmentReplacement(automatic)),
                    document.categoryProbabilities(), document.tierProfiles()));
        }
        GroundLootEntity ground(ItemStack item, int quantity) {
            GroundLootEntity entity = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
            if (entity == null) throw new AssertionError("Ground carrier registration missing");
            entity.initialize(item, quantity, player.blockPosition().below(), 0);
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
            drops.forEach(GroundLootEntity::discard);
            player.getInventory().clearContent();
            player.containerMenu.setCarried(ItemStack.EMPTY);
            for (int i = 1; i <= 4; i++) player.inventoryMenu.getSlot(i).set(ItemStack.EMPTY);
            player.discard();
        }
    }
}
