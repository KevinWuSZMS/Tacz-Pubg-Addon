package com.example.examplemod.backpack;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real registry, player.dat, vanilla inventories and temporary menu inputs in the isolated test save. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class MatchInventorySnapshotGameTests {
    private static final String SNAPSHOT = "tacz_pubg_match_inventory";

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void fullInventoryAndTemporaryInputsSurviveDiskReload(GameTestHelper helper) throws Exception {
        var profile = new GameProfile(UUID.randomUUID(), "[snapshot]");
        var player = FakePlayerFactory.get(helper.getLevel(), profile);
        position(helper, player);
        try {
            player.getInventory().clearContent();
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            var helmet = new ItemStack(Items.IRON_HELMET);
            helmet.setDamageValue(37);
            helmet.set(DataComponents.CUSTOM_NAME, Component.literal("original helmet"));
            player.setItemSlot(EquipmentSlot.HEAD, helmet);
            player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            ItemStack originalPack = new ItemStack(BackpackRegistration.LEVEL_2.get());
            originalPack.set(DataComponents.CUSTOM_NAME, Component.literal("original independent backpack"));
            BackpackService.setEquipped(player, originalPack);
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            player.getInventory().selected = 4;
            ListTag originals = player.getInventory().save(new ListTag()).copy();

            List<ItemStack> extras = new ArrayList<>();
            ItemStack cursor = new ItemStack(Items.ENDER_PEARL, 7);
            extras.add(cursor.copy());
            for (int i = 0; i < 4; i++) {
                ItemStack input = new ItemStack(Items.DIAMOND, i + 1);
                player.inventoryMenu.getSlot(i + 1).set(input);
                extras.add(input.copy());
            }
            var anvil = new AnvilMenu(19, player.getInventory(),
                    ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)));
            player.containerMenu = anvil;
            anvil.setCarried(cursor);
            var sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(100);
            anvil.getSlot(0).set(sword);
            anvil.getSlot(1).set(new ItemStack(Items.IRON_INGOT, 3));
            extras.add(sword.copy());
            extras.add(anvil.getSlot(1).getItem().copy());
            helper.assertTrue(!anvil.getSlot(2).getItem().isEmpty(), "Fixture must contain an anvil output preview");

            MatchInventorySnapshots.begin(player, UUID.randomUUID());
            helper.assertTrue(player.getInventory().isEmpty(), "Begin left pre-match inventory behind");
            helper.assertTrue(BackpackService.equipped(player).isEmpty(), "Begin left the independent backpack behind");
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "Begin left a cursor item behind");
            for (int i = 1; i <= 4; i++)
                helper.assertTrue(player.inventoryMenu.getSlot(i).getItem().isEmpty(), "Hidden personal input leaked into match");
            helper.assertTrue(anvil.getSlot(0).getItem().isEmpty() && anvil.getSlot(1).getItem().isEmpty(),
                    "Closing anvil returned original inputs into the cleared match inventory");
            helper.assertTrue(player.getPersistentData().contains(SNAPSHOT), "Original snapshot marker missing");
            var file = helper.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR)
                    .resolve(player.getStringUUID() + ".dat");
            var saved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            helper.assertTrue(saved.getList("Inventory", 10).isEmpty(), "Disk inventory was not cleared atomically");

            // New entity, same UUID: neither in-memory originals nor the previous menu can help recovery.
            var reconnected = new FakePlayer(helper.getLevel(), profile);
            reconnected.load(saved);
            position(helper, reconnected);
            helper.assertTrue(reconnected.getPersistentData().contains(SNAPSHOT), "Snapshot did not survive player.dat load");
            reconnected.getInventory().setItem(0, new ItemStack(Items.NETHERITE_BLOCK, 3));
            MatchInventorySnapshots.restoreIfStale(reconnected);
            helper.assertTrue(originals.equals(reconnected.getInventory().save(new ListTag())),
                    "Restored inventory differs in slots, counts, equipment, damage or components");
            helper.assertTrue(ItemStack.matches(originalPack, BackpackService.equipped(reconnected)),
                    "Independent backpack did not survive atomic snapshot and reconnect");
            helper.assertTrue(reconnected.getInventory().selected == 4, "Selected hotbar slot was not restored");
            helper.assertTrue(!reconnected.getPersistentData().contains(SNAPSHOT), "Committed restore retained snapshot marker");
            assertSameMultiset(helper, extras, MatchInventorySnapshots.recovery(reconnected));
            var beforeSecondRestore = reconnected.getInventory().save(new ListTag()).copy();
            MatchInventorySnapshots.restoreIfStale(reconnected);
            helper.assertTrue(beforeSecondRestore.equals(reconnected.getInventory().save(new ListTag())),
                    "Repeated restore duplicated originals");
            assertSameMultiset(helper, extras, MatchInventorySnapshots.recovery(reconnected));

            // A full native inventory keeps every extra in persistent recovery, then returns one safely.
            MatchInventorySnapshots.takeRecovery(reconnected, 0);
            assertSameMultiset(helper, extras, MatchInventorySnapshots.recovery(reconnected));
            reconnected.getInventory().setItem(10, ItemStack.EMPTY);
            MatchInventorySnapshots.takeRecovery(reconnected, 0);
            helper.assertTrue(ItemStack.matches(cursor, reconnected.getInventory().getItem(10)),
                    "Recovery did not move the exact cursor stack into the available native slot");
            extras.removeFirst();
            assertSameMultiset(helper, extras, MatchInventorySnapshots.recovery(reconnected));
            var committed = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            var loadedAgain = new FakePlayer(helper.getLevel(), profile);
            loadedAgain.load(committed);
            helper.assertTrue(!loadedAgain.getPersistentData().contains(SNAPSHOT),
                    "Disk restore could restore originals a second time after another reconnect");
            helper.assertTrue(ItemStack.matches(cursor, loadedAgain.getInventory().getItem(10)),
                    "Claimed recovery item did not persist with recovery removal");
            assertSameMultiset(helper, extras, MatchInventorySnapshots.recovery(loadedAgain));
            helper.succeed();
        } finally {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.getInventory().clearContent();
        }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void deathCloneRetainsOriginalsExactlyOnce(GameTestHelper helper) {
        var profile = new GameProfile(UUID.randomUUID(), "[snapshot-clone]");
        var original = FakePlayerFactory.get(helper.getLevel(), profile);
        position(helper, original);
        original.getInventory().clearContent();
        original.getInventory().setItem(8, new ItemStack(Items.EMERALD, 11));
        original.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        ItemStack originalPack = new ItemStack(BackpackRegistration.LEVEL_3.get());
        BackpackService.setEquipped(original, originalPack);
        ListTag expected = original.getInventory().save(new ListTag()).copy();
        MatchInventorySnapshots.begin(original, UUID.randomUUID());
        var replacement = new FakePlayer(helper.getLevel(), profile);
        position(helper, replacement);
        MatchInventorySnapshots.clonePlayer(original, replacement);
        helper.assertTrue(replacement.getPersistentData().contains(SNAPSHOT), "Death clone lost saved originals");
        MatchInventorySnapshots.restoreIfStale(replacement);
        helper.assertTrue(expected.equals(replacement.getInventory().save(new ListTag())), "Death clone recovery lost or duplicated items");
        helper.assertTrue(ItemStack.matches(originalPack, BackpackService.equipped(replacement)), "Death clone lost original independent pack");
        helper.assertTrue(!replacement.getPersistentData().contains(SNAPSHOT) && !MatchInventorySnapshots.hasRecovery(replacement),
                "Death clone recovery retained a second claim");
        MatchInventorySnapshots.restoreIfStale(replacement);
        helper.assertTrue(expected.equals(replacement.getInventory().save(new ListTag())), "Death clone restored twice");
        helper.succeed();
    }

    private static void position(GameTestHelper helper, ServerPlayer player) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
        player.getAbilities().instabuild = false;
    }
    private static void assertSameMultiset(GameTestHelper helper, List<ItemStack> expected, List<ItemStack> actual) {
        List<ItemStack> remaining = new ArrayList<>(actual);
        for (ItemStack item : expected) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) if (ItemStack.matches(item, remaining.get(i))) { match = i; break; }
            helper.assertTrue(match >= 0, "Original temporary item was lost or changed: " + item);
            remaining.remove(match);
        }
        helper.assertTrue(remaining.isEmpty(), "Unexpected extra item, possibly a duplicated recipe output");
    }
}
