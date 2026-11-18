package com.example.examplemod.inventory;

import com.mojang.authlib.GameProfile;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootWorldPhysics;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.example.examplemod.lootspawn.RoundLootService;
import com.example.examplemod.backpack.BackpackRegistration;
import com.example.examplemod.backpack.BackpackService;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.util.AttachmentDataUtils;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real TaCZ attachment codecs and inventory quantities across failed candidate attempts. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class PubgInventoryAttachmentGameTests {
    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void rightClickSkipsAnAlreadyInstalledSight(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            ItemStack held = gun(helper), second = gun(helper), incoming = attachment("sight_t2");
            IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, incoming.copyWithCount(1));
            ItemStack originalHeld = held.copy(); incoming.setCount(2);
            player.getInventory().setItem(0, held); player.getInventory().setItem(1, second);
            player.getInventory().setItem(9, incoming);
            helper.assertTrue(rightClick(player, 9), "An equal attachment on the held gun hid the empty compatible second gun");
            helper.assertTrue(ItemStack.matches(originalHeld, player.getInventory().getItem(0))
                            && player.getInventory().getItem(9).getCount() == 1
                            && ItemStack.matches(incoming.copyWithCount(1), api.getAttachment(player.registryAccess(),
                                player.getInventory().getItem(1), AttachmentType.SCOPE)),
                    "Candidate fallback modified the first gun, lost components, or consumed more than one sight");
            ItemStack[] before = snapshot(player);
            helper.assertTrue(!rightClick(player, 9), "Installing an already-equipped sight into both guns should fail");
            assertUnchanged(helper, player, before);
            helper.assertTrue(!failures().containsKey(player.getUUID()), "A same-attachment no-op leaked another candidate's error");
            helper.succeed();
        } finally { cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void smallerHeldMagazineReturnsExcessWithoutSwitchingGuns(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            for (boolean full : new boolean[] {false, true}) {
                player.getInventory().clearContent();
                ItemStack held = gun(helper), second = gun(helper), incoming = attachment("extended_mag_1");
                ItemStack old = named(attachment("extended_mag_3"), "held larger magazine");
                IGun api = (IGun) held.getItem();
                api.installAttachment(player.registryAccess(), held, old);
                var data = TimelessAPI.getCommonGunIndex(api.getGunId(held)).orElseThrow().getGunData();
                int highCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(held, data);
                ItemStack smaller = held.copy(); api.installAttachment(player.registryAccess(), smaller, incoming);
                int lowCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(smaller, data);
                helper.assertTrue(highCapacity > lowCapacity, "The loaded-magazine fixture must have distinct capacities");
                api.setCurrentAmmoCount(held, lowCapacity + 1); api.setBulletInBarrel(held, true);
                ItemStack originalSecond = second.copy(); incoming.setCount(2);
                player.getInventory().setItem(0, held); player.getInventory().setItem(1, second);
                if (full) fillStorage(player);
                player.getInventory().setItem(9, incoming);
                helper.assertTrue(rightClick(player, 9), "Smaller held magazine did not return its excess round");
                ItemStack finalHeld = player.getInventory().getItem(0);
                helper.assertTrue(ItemStack.matches(originalSecond, player.getInventory().getItem(1))
                                && player.getInventory().getItem(9).getCount() == 1
                                && ItemStack.matches(incoming.copyWithCount(1), api.getAttachment(player.registryAccess(), finalHeld, AttachmentType.EXTENDED_MAG))
                                && api.getCurrentAmmoCount(finalHeld) == lowCapacity && api.hasBulletInBarrel(finalHeld)
                                && ammoCount(player, data.getAmmoId(), false) == (full ? 0 : 1)
                                && ammoCount(player, data.getAmmoId(), true) == (full ? 1 : 0)
                                && carried(player, old) == (full ? 0 : 1) && groundCount(player, old) == (full ? 1 : 0),
                        "Magazine downgrade changed the other gun, lost chamber/excess ammunition, or duplicated an attachment");
                helper.assertTrue(!failures().containsKey(player.getUUID()), "Successful excess-round return retained a stale capacity error");
            }
            helper.succeed();
        } finally { discardTransactionDrops(player); cleanup(player); }
    }
    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void installedSightTransfersReturnDisplacedComponentsExactlyOnce(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            ItemStack source = gun(helper), destination = gun(helper);
            ItemStack sight = named(attachment("sight_t2"), "source sight"), displaced = named(attachment("sight_t2"), "old destination sight");
            IGun api = (IGun) source.getItem();
            api.installAttachment(player.registryAccess(), source, sight);
            api.installAttachment(player.registryAccess(), destination, displaced);
            player.getInventory().setItem(0, source); player.getInventory().setItem(1, destination);
            helper.assertTrue(transfer(player, 0, 1, AttachmentType.SCOPE), "Compatible gun-to-gun transfer failed");
            helper.assertTrue(ItemStack.matches(displaced, api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE))
                            && ItemStack.matches(sight, api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE))
                            && carried(player, displaced) == 0 && carried(player, sight) == 0,
                    "Gun-to-gun transfer lost, duplicated, or stripped attachment components");
            helper.assertTrue(transfer(player, 1, -1, AttachmentType.SCOPE)
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE).isEmpty()
                            && carried(player, sight) == 1 && carried(player, displaced) == 0,
                    "Unloading the transferred attachment did not return exactly one physical item");
            ItemStack[] before = snapshot(player);
            helper.assertTrue(!transfer(player, 1, -2, AttachmentType.SCOPE), "An empty/built-in-only slot produced a physical attachment");
            assertUnchanged(helper, player, before);
            helper.succeed();
        } finally { cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void installedAttachmentRejectionsPreserveBothGuns(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            ItemStack source = gun(helper), destination = gun(helper);
            IGun api = (IGun) source.getItem();
            ItemStack sight = named(attachment("sight_t2"), "source"), displaced = named(attachment("sight_t2"), "destination");
            api.installAttachment(player.registryAccess(), source, sight);
            api.installAttachment(player.registryAccess(), destination, displaced);
            player.getInventory().setItem(0, source); player.getInventory().setItem(1, destination);
            api.setAttachmentLock(source, true);
            ItemStack[] before = snapshot(player);
            for (int target : new int[] {-2, -1, 1}) {
                helper.assertTrue(!transfer(player, 0, target, AttachmentType.SCOPE), "Locked source allowed attachment extraction");
                assertUnchanged(helper, player, before);
            }
            api.setAttachmentLock(source, false); api.setAttachmentLock(destination, true);
            before = snapshot(player);
            helper.assertTrue(!transfer(player, 0, 1, AttachmentType.SCOPE), "Locked destination accepted an attachment");
            assertUnchanged(helper, player, before);
            api.setAttachmentLock(destination, false);
            for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            helper.assertTrue(transfer(player, 0, 1, AttachmentType.SCOPE), "A direct two-gun exchange unnecessarily required inventory space");
            helper.assertTrue(ItemStack.matches(displaced, api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE))
                            && ItemStack.matches(sight, api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE)),
                    "Full-inventory exchange did not preserve both attachments");
            helper.succeed();
        } finally { cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void loadedMagazineRemovalReturnsExcessFromTheFinalSourceGun(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            for (boolean full : new boolean[] {false, true}) for (int target : new int[] {-2, -1, 1}) {
                player.getInventory().clearContent(); discardTransactionDrops(player);
                ItemStack source = gun(helper), destination = gun(helper), magazine = named(attachment("extended_mag_3"), "removed loaded magazine");
                IGun api = (IGun) source.getItem();
                var data = TimelessAPI.getCommonGunIndex(api.getGunId(source)).orElseThrow().getGunData();
                int baseCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(source, data);
                api.installAttachment(player.registryAccess(), source, magazine);
                api.setCurrentAmmoCount(source, baseCapacity + 1); api.setBulletInBarrel(source, true);
                player.getInventory().setItem(0, source); player.getInventory().setItem(1, destination);
                if (full) fillStorage(player);
                helper.assertTrue(transfer(player, 0, target, AttachmentType.EXTENDED_MAG), "Removing or transferring a loaded magazine did not settle excess rounds");
                ItemStack finalSource = player.getInventory().getItem(0), finalTarget = player.getInventory().getItem(1);
                int carriedMagazines = target == -1 && !full ? 1 : 0;
                int droppedMagazines = target == -2 || target == -1 && full ? 1 : 0;
                helper.assertTrue(api.getAttachment(player.registryAccess(), finalSource, AttachmentType.EXTENDED_MAG).isEmpty()
                                && api.getCurrentAmmoCount(finalSource) == baseCapacity && api.hasBulletInBarrel(finalSource)
                                && api.getCurrentAmmoCount(finalTarget) == 0 && !api.hasBulletInBarrel(finalTarget)
                                && ammoCount(player, data.getAmmoId(), false) == (full ? 0 : 1)
                                && ammoCount(player, data.getAmmoId(), true) == (full ? 1 : 0)
                                && carried(player, magazine) == carriedMagazines && groundCount(player, magazine) == droppedMagazines
                                && (target != 1 || ItemStack.matches(magazine, api.getAttachment(player.registryAccess(), finalTarget, AttachmentType.EXTENDED_MAG))),
                        "Source magazine removal lost or duplicated ammunition/chamber state or returned the physical magazine incorrectly");
            }
            helper.succeed();
        } finally { discardTransactionDrops(player); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void magazineExchangeReturnsExcessFromTheFinalDestinationGun(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            for (boolean full : new boolean[] {false, true}) {
                player.getInventory().clearContent(); discardTransactionDrops(player);
                ItemStack source = gun(helper), destination = gun(helper), small = attachment("extended_mag_1"), large = attachment("extended_mag_3");
                IGun api = (IGun) source.getItem();
                api.installAttachment(player.registryAccess(), source, small);
                api.installAttachment(player.registryAccess(), destination, large);
                var data = TimelessAPI.getCommonGunIndex(api.getGunId(source)).orElseThrow().getGunData();
                int smallerCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(source, data);
                api.setCurrentAmmoCount(destination, smallerCapacity + 1); api.setBulletInBarrel(destination, true);
                player.getInventory().setItem(0, source); player.getInventory().setItem(1, destination);
                if (full) fillStorage(player);
                helper.assertTrue(transfer(player, 0, 1, AttachmentType.EXTENDED_MAG), "Legal magazine exchange did not return the destination's excess ammunition");
                ItemStack finalSource = player.getInventory().getItem(0), finalTarget = player.getInventory().getItem(1);
                helper.assertTrue(ItemStack.matches(large, api.getAttachment(player.registryAccess(), finalSource, AttachmentType.EXTENDED_MAG))
                                && ItemStack.matches(small, api.getAttachment(player.registryAccess(), finalTarget, AttachmentType.EXTENDED_MAG))
                                && api.getCurrentAmmoCount(finalSource) == 0 && !api.hasBulletInBarrel(finalSource)
                                && api.getCurrentAmmoCount(finalTarget) == smallerCapacity && api.hasBulletInBarrel(finalTarget)
                                && ammoCount(player, data.getAmmoId(), false) == (full ? 0 : 1)
                                && ammoCount(player, data.getAmmoId(), true) == (full ? 1 : 0),
                        "Destination magazine downgrade lost its chamber round, excess ammunition, or either physical magazine");
            }
            helper.succeed();
        } finally { discardTransactionDrops(player); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void installedAttachmentDropIsImmediateAndCanceledSpawnIsAtomic(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        var spawned = new java.util.ArrayList<GroundLootEntity>();
        try {
            ItemStack gun = gun(helper), sight = named(attachment("sight_t2"), "ground attachment");
            IGun api = (IGun) gun.getItem(); api.installAttachment(player.registryAccess(), gun, sight);
            player.getInventory().setItem(0, gun);
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof GroundLootEntity loot && ItemStack.isSameItemSameComponents(sight, loot.getItem()))
                    event.setCanceled(true);
            };
            ItemStack[] before = snapshot(player);
            NeoForge.EVENT_BUS.addListener(veto);
            try { helper.assertTrue(!transfer(player, 0, -2, AttachmentType.SCOPE), "Canceled attachment ground spawn reported success"); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            assertUnchanged(helper, player, before);
            helper.assertTrue(transfer(player, 0, -2, AttachmentType.SCOPE), "Installed attachment could not be dropped");
            spawned.addAll(player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2),
                    loot -> ItemStack.isSameItemSameComponents(sight, loot.getItem())));
            helper.assertTrue(spawned.size() == 1 && spawned.getFirst().getQuantity() == 1
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty()
                            && carried(player, sight) == 0 && PubgInventoryService.nearby(player, spawned.getFirst())
                            && !spawned.getFirst().canBePickedUpBy(player),
                    "Dropped attachment was duplicated, delayed in nearby, or bypassed pickup delay");
            helper.succeed();
        } finally { spawned.forEach(GroundLootEntity::discard); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void rightClickPrioritizesTheActuallyHeldSecondGun(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            ItemStack first = gun(helper), held = gun(helper), incoming = named(attachment("sight_t2"), "held second");
            IGun api = (IGun) held.getItem();
            player.getInventory().setItem(0, first); player.getInventory().setItem(1, held);
            player.getInventory().setItem(9, incoming); player.getInventory().selected = 1;
            helper.assertTrue(rightClick(player, 9), "The held second gun rejected a compatible sight");
            helper.assertTrue(api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty()
                            && ItemStack.matches(incoming, api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE))
                            && player.getInventory().getItem(9).isEmpty(), "Right click preferred slot zero over the actual held gun");
            helper.succeed();
        } finally { cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void loadedMagazineExchangeChecksBothFinalCapacities(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            ItemStack source = gun(helper), target = gun(helper);
            IGun api = (IGun) source.getItem();
            ItemStack first = named(attachment("extended_mag_3"), "first magazine");
            ItemStack second = named(attachment("extended_mag_3"), "second magazine");
            int base = AttachmentDataUtils.getAmmoCountWithAttachment(source,
                    TimelessAPI.getCommonGunIndex(api.getGunId(source)).orElseThrow().getGunData());
            api.installAttachment(player.registryAccess(), source, first); api.installAttachment(player.registryAccess(), target, second);
            api.setCurrentAmmoCount(source, base + 1); api.setBulletInBarrel(source, true);
            api.setCurrentAmmoCount(target, base + 2); api.setBulletInBarrel(target, true);
            player.getInventory().setItem(0, source); player.getInventory().setItem(1, target);
            helper.assertTrue(transfer(player, 0, 1, AttachmentType.EXTENDED_MAG), "Temporary empty-magazine state rejected a legal final exchange");
            ItemStack finalSource = player.getInventory().getItem(0), finalTarget = player.getInventory().getItem(1);
            helper.assertTrue(ItemStack.matches(second, api.getAttachment(player.registryAccess(), finalSource, AttachmentType.EXTENDED_MAG))
                            && ItemStack.matches(first, api.getAttachment(player.registryAccess(), finalTarget, AttachmentType.EXTENDED_MAG))
                            && api.getCurrentAmmoCount(finalSource) == base + 1 && api.hasBulletInBarrel(finalSource)
                            && api.getCurrentAmmoCount(finalTarget) == base + 2 && api.hasBulletInBarrel(finalTarget),
                    "Final magazine exchange altered rounds, chambers, or attachment components");
            helper.succeed();
        } finally { cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void fullInventoryReplacementAndUnloadFallBackToGroundAtomically(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack old = named(attachment("sight_t2"), "old sight full"), incoming = named(attachment("sight_t2"), "new sight full");
        try {
            ItemStack held = gun(helper); IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, old);
            player.getInventory().setItem(0, held);
            for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            player.getInventory().setItem(9, incoming.copyWithCount(2));
            ItemStack[] before = snapshot(player);
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof GroundLootEntity loot && ItemStack.isSameItemSameComponents(old, loot.getItem()))
                    event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(veto);
            try { helper.assertTrue(!rightClick(player, 9), "Rejected displaced-item spawn still committed replacement"); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            assertUnchanged(helper, player, before);
            helper.assertTrue(rightClick(player, 9) && groundCount(player, old) == 1
                            && player.getInventory().getItem(9).getCount() == 1
                            && ItemStack.matches(incoming, api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE)),
                    "Full inventory replacement lost or duplicated old/new attachment components");
            player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
            helper.assertTrue(transfer(player, 0, -1, AttachmentType.SCOPE) && groundCount(player, incoming) == 1
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty(),
                    "Unload failed to put an unstoreable attachment on the ground");
            helper.succeed();
        } finally { discardGround(player, old, incoming); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void dropCallbackChangesAbortBeforeSourceConsumption(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack sight = named(attachment("sight_t2"), "callback sight");
        try {
            ItemStack held = gun(helper); IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, sight);
            player.getInventory().setItem(0, held);
            ItemStack original = held.copy();
            Consumer<EntityJoinLevelEvent> mutate = event -> {
                if (event.getEntity() instanceof GroundLootEntity loot && ItemStack.isSameItemSameComponents(sight, loot.getItem()))
                    player.getInventory().setItem(10, new ItemStack(Items.DIRT));
            };
            NeoForge.EVENT_BUS.addListener(mutate);
            try { helper.assertTrue(!transfer(player, 0, -2, AttachmentType.SCOPE), "A changed inventory snapshot still committed"); }
            finally { NeoForge.EVENT_BUS.unregister(mutate); }
            helper.assertTrue(ItemStack.matches(original, player.getInventory().getItem(0)) && groundCount(player, sight) == 0
                            && player.getInventory().getItem(10).is(Items.DIRT),
                    "Aborted drop lost its source, leaked a duplicate, or overwrote an unrelated callback mutation");
            helper.succeed();
        } finally { discardGround(player, sight); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void explicitGroundStorageNeverAutoEquipsAttachmentsOrBackpacks(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack sight = named(attachment("sight_t2"), "store only");
        ItemStack pack = named(new ItemStack(BackpackRegistration.LEVEL_1.get()), "store only pack");
        GroundLootEntity source = ground(player, sight.copyWithCount(3)), packSource = null;
        try {
            ItemStack held = gun(helper); IGun api = (IGun) held.getItem(); player.getInventory().setItem(0, held);
            helper.assertTrue(move(player, -1, -1, source), "Explicit ground storage rejected collectable sights");
            helper.assertTrue(source.isRemoved() && carried(player, sight) == 3
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty(),
                    "Ground-to-list drag auto-equipped a sight or lost its remaining quantity");
            packSource = ground(player, pack);
            helper.assertTrue(!move(player, -1, -1, packSource) && BackpackService.equipped(player).isEmpty()
                            && !packSource.isRemoved() && packSource.getQuantity() == 1,
                    "Unstoreable backpack bypassed the explicit storage route and equipped itself");
            helper.succeed();
        } finally { source.discard(); if (packSource != null) packSource.discard(); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void groundAndIndependentBackpackReplacementConserveBothItems(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack previous = named(new ItemStack(BackpackRegistration.LEVEL_1.get()), "previous pack");
        ItemStack replacement = named(new ItemStack(BackpackRegistration.LEVEL_2.get()), "replacement pack");
        GroundLootEntity source = ground(player, replacement);
        try {
            BackpackService.setEquipped(player, previous);
            Consumer<EntityJoinLevelEvent> veto = event -> {
                if (event.getEntity() instanceof GroundLootEntity loot && ItemStack.isSameItemSameComponents(previous, loot.getItem())) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(veto);
            try { helper.assertTrue(!move(player, -1, 41, source), "Canceled old-backpack spawn consumed the new backpack"); }
            finally { NeoForge.EVENT_BUS.unregister(veto); }
            helper.assertTrue(ItemStack.matches(previous, BackpackService.equipped(player)) && !source.isRemoved()
                            && source.getQuantity() == 1 && carried(player, previous) == 0,
                    "Canceled ground backpack replacement changed equipment or quantity");
            helper.assertTrue(move(player, -1, 41, source) && source.isRemoved()
                            && ItemStack.matches(replacement, BackpackService.equipped(player)) && groundCount(player, previous) == 1,
                    "Ground backpack replacement failed to preserve both named physical backpacks");
            player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
            helper.assertTrue(move(player, 41, -1, null) && BackpackService.equipped(player).isEmpty()
                            && groundCount(player, replacement) == 1 && player.getInventory().getItem(9).getCount() == 64,
                    "Unstoreable worn backpack was not dropped without disturbing retained contents");
            helper.succeed();
        } finally { source.discard(); discardGround(player, previous, replacement); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void groundPickupPolicyControlsDirectReplacementAndStorage(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack old = named(attachment("sight_t2"), "old held sight");
        ItemStack incoming = named(attachment("scope_elcan_4x"), "new ground scope");
        GroundLootEntity first = ground(player, incoming.copyWithCount(2)), second = null;
        try {
            ItemStack held = gun(helper); IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, old);
            player.getInventory().setItem(0, gun(helper)); player.getInventory().setItem(1, held); player.getInventory().selected = 1;
            helper.assertTrue(pickup(player, first, true), "Direct pickup did not replace the held second gun's sight");
            helper.assertTrue(first.getQuantity() == 1 && carried(player, old) == 1 && carried(player, incoming) == 0
                            && ItemStack.matches(incoming, api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE))
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty(),
                    "Direct pickup changed the wrong gun or failed to conserve the returned item and ground remainder");
            second = ground(player, old.copyWithCount(2));
            ItemStack[] before = snapshot(player);
            helper.assertTrue(pickup(player, second, false) && second.isRemoved() && carried(player, old) == 3
                            && ItemStack.matches(before[0], player.getInventory().getItem(0))
                            && ItemStack.matches(before[1], player.getInventory().getItem(1)),
                    "Disabled direct pickup still equipped a sight or consumed an incorrect amount");
            helper.succeed();
        } finally { first.discard(); if (second != null) second.discard(); discardGround(player, old, incoming); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void groundSourceComponentMutationCannotConsumeAnotherItem(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack old = named(attachment("sight_t2"), "old forcing a drop");
        ItemStack incoming = named(attachment("scope_elcan_4x"), "original ground source");
        GroundLootEntity ground = ground(player, incoming);
        try {
            ItemStack held = gun(helper); IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, old); player.getInventory().setItem(0, held);
            for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            ItemStack[] before = snapshot(player);
            Consumer<EntityJoinLevelEvent> mutate = event -> {
                if (event.getEntity() instanceof GroundLootEntity loot && ItemStack.isSameItemSameComponents(old, loot.getItem()))
                    ground.getItem().set(DataComponents.CUSTOM_NAME, Component.literal("callback changed source"));
            };
            NeoForge.EVENT_BUS.addListener(mutate);
            try { helper.assertTrue(!pickup(player, ground, true), "A changed ground ItemStack alias bypassed the expected-source check"); }
            finally { NeoForge.EVENT_BUS.unregister(mutate); }
            assertUnchanged(helper, player, before);
            helper.assertTrue(!ground.isRemoved() && ground.getQuantity() == 1 && groundCount(player, old) == 0
                            && ground.getItem().getHoverName().getString().equals("callback changed source"),
                    "Aborted attachment replacement consumed the changed source or leaked its old attachment");
            helper.succeed();
        } finally { ground.discard(); discardGround(player, old, incoming); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void incompatibleDisplacedSniperScopeReturnsToInventoryThenGround(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack redDot = named(attachment("sight_t2"), "rifle red dot"), sniperScope = named(attachment("scope_standard_8x"), "sniper eight power");
        try {
            for (boolean full : new boolean[] {false, true}) {
                player.getInventory().clearContent();
                ItemStack rifle = gun(helper), sniper = gun(helper, "ai_awp"); IGun api = (IGun) rifle.getItem();
                helper.assertTrue(api.allowAttachment(rifle, redDot) && api.allowAttachment(sniper, redDot)
                                && api.allowAttachment(sniper, sniperScope) && !api.allowAttachment(rifle, sniperScope),
                        "Real M4A1/AWP sight compatibility fixture no longer exercises an incompatible return");
                api.installAttachment(player.registryAccess(), rifle, redDot);
                api.installAttachment(player.registryAccess(), sniper, sniperScope);
                api.setCurrentAmmoCount(rifle, 7); api.setBulletInBarrel(rifle, true);
                api.setCurrentAmmoCount(sniper, 2); api.setBulletInBarrel(sniper, true);
                player.getInventory().setItem(0, rifle); player.getInventory().setItem(1, sniper);
                if (full) for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                helper.assertTrue(transfer(player, 0, 1, AttachmentType.SCOPE), "Incompatible old scope blocked legal outgoing sight transfer");
                ItemStack nextRifle = player.getInventory().getItem(0), nextSniper = player.getInventory().getItem(1);
                helper.assertTrue(api.getAttachment(player.registryAccess(), nextRifle, AttachmentType.SCOPE).isEmpty()
                                && ItemStack.matches(redDot, api.getAttachment(player.registryAccess(), nextSniper, AttachmentType.SCOPE))
                                && api.getCurrentAmmoCount(nextRifle) == 7 && api.hasBulletInBarrel(nextRifle)
                                && api.getCurrentAmmoCount(nextSniper) == 2 && api.hasBulletInBarrel(nextSniper)
                                && carried(player, sniperScope) == (full ? 0 : 1) && groundCount(player, sniperScope) == (full ? 1 : 0),
                        "Incompatible scope fallback lost components, duplicated a scope, or altered gun ammunition");
            }
            helper.succeed();
        } finally { discardGround(player, redDot, sniperScope); cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void realRoundProvenanceDoesNotRejectAnOtherwiseAtomicDrop(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        ItemStack sight = named(attachment("sight_t2"), "round provenance");
        var map = new com.example.examplemod.zones.MapZoneModel.MapDefinition(987_654_321L, "inventory round drop",
                player.level().dimension().location(), player.getX() - 1, player.getZ() - 1,
                player.getX() + 1, player.getZ() + 1, null, 1, java.util.List.of());
        boolean begun = false;
        try {
            ItemStack held = gun(helper); IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, sight); player.getInventory().setItem(0, held);
            helper.assertTrue(RoundLootService.MARK != null && !sight.has(RoundLootService.MARK), "Fixture must begin with an unmarked physical attachment");
            RoundLootService.begin(player.server, map); begun = true;
            helper.assertTrue(transfer(player, 0, -2, AttachmentType.SCOPE), "Normal round provenance tagging was mistaken for drop substitution");
            var drops = player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(1),
                    loot -> loot.getItem().getHoverName().getString().equals("round provenance"));
            helper.assertTrue(drops.size() == 1 && drops.getFirst().getQuantity() == 1
                            && drops.getFirst().getItem().has(RoundLootService.MARK), "Real round drop lacks its expected provenance or physical count");
            ItemStack normalized = drops.getFirst().getItem().copy(); normalized.remove(RoundLootService.MARK);
            helper.assertTrue(ItemStack.matches(sight, normalized) && !sight.has(RoundLootService.MARK)
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty(),
                    "Provenance preparation changed source components or retained the dropped attachment on the gun");
            helper.succeed();
        } finally { if (begun) RoundLootService.end(player.server, map); cleanup(player); }
    }

    private static GroundLootEntity ground(FakePlayer player, ItemStack item) {
        GroundLootEntity result = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
        if (result == null) throw new AssertionError("Ground loot type unavailable");
        BlockPos floor = player.blockPosition().below();
        player.level().setBlockAndUpdate(floor, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        result.initializeDrop(item, floor, 0, null);
        result.setPos(player.getX(), floor.getY() + 1.0, player.getZ());
        if (result.canBePickedUpBy(player)) throw new AssertionError("Newly falling fixture bypassed the resting requirement");
        if (!player.level().addFreshEntity(result)) throw new AssertionError("Ground loot fixture was canceled");
        GroundLootWorldPhysics.prepareSpawn(result);
        GroundLootWorldPhysics.tick(result);
        if (!result.isResting() || !result.canBePickedUpBy(player) || !PubgInventoryService.nearby(player, result)) {
            result.discard();
            throw new AssertionError("Ground fixture must have physically landed within visible pickup range: resting="
                    + result.isResting() + ", blocked=" + result.isPhysicsBlocked() + ", position=" + result.position()
                    + ", player=" + player.position());
        }
        return result;
    }
    private static int groundCount(FakePlayer player, ItemStack item) {
        return player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2),
                loot -> ItemStack.isSameItemSameComponents(item, loot.getItem())).stream().mapToInt(GroundLootEntity::getQuantity).sum();
    }
    private static void fillStorage(FakePlayer player) {
        for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
    }
    private static int ammoCount(FakePlayer player, ResourceLocation id, boolean ground) {
        if (ground) return player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2),
                loot -> loot.getItem().getItem() instanceof IAmmo ammo && ammo.getAmmoId(loot.getItem()).equals(id))
                .stream().mapToInt(GroundLootEntity::getQuantity).sum();
        int count = 0;
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() instanceof IAmmo ammo && ammo.getAmmoId(stack).equals(id)) count += stack.getCount();
        }
        return count;
    }
    private static void discardTransactionDrops(FakePlayer player) {
        player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2)).forEach(GroundLootEntity::discard);
    }
    private static void discardGround(FakePlayer player, ItemStack... items) {
        for (ItemStack item : items) player.level().getEntitiesOfClass(GroundLootEntity.class, player.getBoundingBox().inflate(2),
                loot -> ItemStack.isSameItemSameComponents(item, loot.getItem())).forEach(GroundLootEntity::discard);
    }
    private static boolean move(ServerPlayer player, int source, int target, GroundLootEntity ground) throws Exception {
        var method = PubgInventoryService.class.getDeclaredMethod("move", ServerPlayer.class, int.class, int.class, GroundLootEntity.class);
        method.setAccessible(true); return (boolean) method.invoke(null, player, source, target, ground);
    }
    private static boolean pickup(ServerPlayer player, GroundLootEntity ground, boolean directEquip) throws Exception {
        var method = PubgInventoryService.class.getDeclaredMethod("pickup", ServerPlayer.class, GroundLootEntity.class, boolean.class);
        method.setAccessible(true); return (boolean) method.invoke(null, player, ground, directEquip);
    }

    private static ItemStack named(ItemStack item, String name) {
        item.set(DataComponents.CUSTOM_NAME, Component.literal(name)); return item;
    }
    private static int carried(FakePlayer player, ItemStack expected) {
        int total = 0;
        for (int i = 0; i < 41; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(expected, item)) total += item.getCount();
        }
        return total;
    }
    private static boolean transfer(ServerPlayer player, int source, int target, AttachmentType type) throws Exception {
        var method = PubgInventoryService.class.getDeclaredMethod("transferAttachment", ServerPlayer.class, int.class, int.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, player, source, target, type.name());
    }
    private static boolean rightClick(ServerPlayer player, int source) throws Exception {
        var method = PubgInventoryService.class.getDeclaredMethod("rightClick", ServerPlayer.class, int.class, ItemStack.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, player, source, player.getInventory().getItem(source));
    }
    @SuppressWarnings("unchecked")
    private static Map<UUID, String> failures() throws Exception {
        var field = PubgInventoryService.class.getDeclaredField("FAILURE"); field.setAccessible(true);
        return (Map<UUID, String>) field.get(null);
    }
    private static ItemStack[] snapshot(FakePlayer player) {
        ItemStack[] items = new ItemStack[41];
        for (int i = 0; i < items.length; i++) items[i] = player.getInventory().getItem(i).copy();
        return items;
    }
    private static void assertUnchanged(GameTestHelper helper, FakePlayer player, ItemStack[] before) {
        for (int i = 0; i < before.length; i++) helper.assertTrue(ItemStack.matches(before[i], player.getInventory().getItem(i)),
                "Rejected candidate modified inventory slot " + i);
    }
    private static ItemStack gun(GameTestHelper helper) {
        return gun(helper, "m4a1");
    }
    private static ItemStack gun(GameTestHelper helper, String id) {
        return GunItemBuilder.create().setId(ResourceLocation.fromNamespaceAndPath("tacz", id))
                .setAmmoCount(0).setAmmoInBarrel(false).build(helper.getLevel().registryAccess());
    }
    private static ItemStack attachment(String path) {
        return AttachmentItemBuilder.create().setId(ResourceLocation.fromNamespaceAndPath("tacz", path)).build();
    }
    private static FakePlayer player(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[pubg-refit]"));
        player.getInventory().clearContent(); player.getInventory().selected = 0;
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
        return player;
    }
    private static void cleanup(FakePlayer player) throws Exception {
        failures().remove(player.getUUID()); BackpackService.setEquipped(player, ItemStack.EMPTY);
        player.getInventory().clearContent(); player.discard();
    }
}
