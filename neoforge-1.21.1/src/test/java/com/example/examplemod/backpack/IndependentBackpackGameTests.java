package com.example.examplemod.backpack;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class IndependentBackpackGameTests {
    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void legacyMigrationFreesTrousersAndPersistsComponents(GameTestHelper helper) {
        FakePlayer player = player(helper);
        ItemStack pack = new ItemStack(BackpackRegistration.LEVEL_2.get());
        pack.set(DataComponents.CUSTOM_NAME, Component.literal("legacy real pack"));
        player.setItemSlot(EquipmentSlot.LEGS, pack.copy());
        BackpackService.migrateLegacy(player);
        BackpackService.migrateLegacy(player);
        helper.assertTrue(player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && ItemStack.matches(pack, BackpackService.equipped(player)), "Migration duplicated or lost the legacy backpack");
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        var saved = player.saveWithoutId(new CompoundTag());
        FakePlayer loaded = player(helper);
        loaded.load(saved);
        helper.assertTrue(loaded.getItemBySlot(EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS)
                && ItemStack.matches(pack, BackpackService.equipped(loaded)), "Backpack and trousers did not persist independently");
        helper.assertTrue(player.getInventory().items.stream().noneMatch(BackpackService::isBackpack),
                "Migration created an extra carried pack");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void versionOneSnapshotRestoresLegacyPackIntoIndependentSlot(GameTestHelper helper) {
        FakePlayer player = player(helper);
        ItemStack oldPack = new ItemStack(BackpackRegistration.LEVEL_3.get());
        player.setItemSlot(EquipmentSlot.LEGS, oldPack);
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("Version", 1);
        snapshot.putUUID("Session", UUID.randomUUID());
        snapshot.put("Inventory", player.getInventory().save(new ListTag()));
        snapshot.put("Extras", new ListTag());
        player.getInventory().clearContent();
        player.getPersistentData().put("tacz_pubg_match_inventory", snapshot);
        MatchInventorySnapshots.restoreIfStale(player);
        helper.assertTrue(player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && ItemStack.matches(oldPack, BackpackService.equipped(player))
                && !player.getPersistentData().contains("tacz_pubg_match_inventory"), "Version-one snapshot migration failed");
        MatchInventorySnapshots.restoreIfStale(player);
        helper.assertTrue(ItemStack.matches(oldPack, BackpackService.equipped(player)), "Second restoration altered the migrated pack");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void deathAndKeepInventoryNeverDuplicateIndependentEquipment(GameTestHelper helper) {
        FakePlayer original = player(helper), replacement = player(helper);
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean before = rule.get();
        ItemStack pack = new ItemStack(BackpackRegistration.LEVEL_1.get());
        try {
            rule.set(false, helper.getLevel().getServer());
            BackpackService.setEquipped(original, pack);
            var drops = new ArrayList<ItemEntity>();
            var event = new LivingDropsEvent(original, original.damageSources().generic(), drops, false);
            BackpackService.drops(event);
            BackpackService.drops(event);
            BackpackService.clonePlayer(new PlayerEvent.Clone(replacement, original, true));
            helper.assertTrue(drops.size() == 1 && ItemStack.matches(pack, drops.getFirst().getItem())
                    && BackpackService.equipped(original).isEmpty() && BackpackService.equipped(replacement).isEmpty(),
                    "Death duplicated or deleted independent equipment");
            rule.set(true, helper.getLevel().getServer());
            BackpackService.setEquipped(original, pack);
            drops.clear();
            BackpackService.drops(event);
            BackpackService.clonePlayer(new PlayerEvent.Clone(replacement, original, true));
            helper.assertTrue(drops.isEmpty() && ItemStack.matches(pack, BackpackService.equipped(replacement)),
                    "Keep-inventory dropped or lost independent equipment");
            helper.succeed();
        } finally { rule.set(before, helper.getLevel().getServer()); }
    }

    private static FakePlayer player(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[independent-pack]"));
        var origin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
        return player;
    }
}
