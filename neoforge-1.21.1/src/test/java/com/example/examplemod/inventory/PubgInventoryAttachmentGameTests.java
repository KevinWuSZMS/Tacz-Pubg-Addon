package com.example.examplemod.inventory;

import com.mojang.authlib.GameProfile;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.tacz.guns.api.TimelessAPI;
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
    public static void rejectedLoadedMagazineDoesNotPolluteTheNextGun(GameTestHelper helper) throws Exception {
        FakePlayer player = player(helper);
        try {
            ItemStack held = gun(helper), second = gun(helper), incoming = attachment("extended_mag_1");
            IGun api = (IGun) held.getItem();
            api.installAttachment(player.registryAccess(), held, attachment("extended_mag_3"));
            var data = TimelessAPI.getCommonGunIndex(api.getGunId(held)).orElseThrow().getGunData();
            int highCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(held, data);
            ItemStack smaller = held.copy(); api.installAttachment(player.registryAccess(), smaller, incoming);
            int lowCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(smaller, data);
            helper.assertTrue(highCapacity > lowCapacity, "The loaded-magazine fixture must have distinct capacities");
            api.setCurrentAmmoCount(held, lowCapacity + 1); api.setBulletInBarrel(held, true);
            ItemStack originalHeld = held.copy(); incoming.setCount(2);
            player.getInventory().setItem(0, held); player.getInventory().setItem(1, second);
            player.getInventory().setItem(9, incoming);
            helper.assertTrue(rightClick(player, 9), "A rejected loaded magazine prevented installation on the other gun");
            helper.assertTrue(ItemStack.matches(originalHeld, player.getInventory().getItem(0))
                            && player.getInventory().getItem(9).getCount() == 1
                            && ItemStack.matches(incoming.copyWithCount(1), api.getAttachment(player.registryAccess(),
                                player.getInventory().getItem(1), AttachmentType.EXTENDED_MAG)),
                    "Failed first candidate changed ammunition, attachments, or source quantity");
            helper.assertTrue(!failures().containsKey(player.getUUID()), "A successful second candidate retained the first gun's magazine error");
            ItemStack[] before = snapshot(player);
            helper.assertTrue(!rightClick(player, 9), "No remaining gun should accept the same smaller magazine");
            assertUnchanged(helper, player, before);
            helper.assertTrue(failures().containsKey(player.getUUID()), "All-candidates failure lost its useful capacity explanation");
            helper.succeed();
        } finally { cleanup(player); }
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
            helper.assertTrue(api.getAttachment(player.registryAccess(), player.getInventory().getItem(0), AttachmentType.SCOPE).isEmpty()
                            && ItemStack.matches(sight, api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE))
                            && carried(player, displaced) == 1 && carried(player, sight) == 0,
                    "Gun-to-gun transfer lost, duplicated, or stripped attachment components");
            helper.assertTrue(transfer(player, 1, -1, AttachmentType.SCOPE)
                            && api.getAttachment(player.registryAccess(), player.getInventory().getItem(1), AttachmentType.SCOPE).isEmpty()
                            && carried(player, sight) == 1 && carried(player, displaced) == 1,
                    "Unloading the transferred attachment did not return exactly one physical item");
            ItemStack[] before = snapshot(player);
            helper.assertTrue(!transfer(player, 0, -2, AttachmentType.SCOPE), "An empty/built-in-only slot produced a physical attachment");
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
            before = snapshot(player);
            helper.assertTrue(!transfer(player, 0, 1, AttachmentType.SCOPE), "Transfer discarded a displaced attachment when inventory was full");
            assertUnchanged(helper, player, before);
            player.getInventory().clearContent();
            source = gun(helper); destination = gun(helper);
            api.installAttachment(player.registryAccess(), source, attachment("extended_mag_3"));
            var data = TimelessAPI.getCommonGunIndex(api.getGunId(source)).orElseThrow().getGunData();
            int baseCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(destination, data);
            api.setCurrentAmmoCount(source, baseCapacity + 1); api.setBulletInBarrel(source, true);
            player.getInventory().setItem(0, source); player.getInventory().setItem(1, destination);
            before = snapshot(player);
            for (int target : new int[] {-2, -1, 1}) {
                helper.assertTrue(!transfer(player, 0, target, AttachmentType.EXTENDED_MAG), "Loaded source magazine could be removed with no room for rounds");
                assertUnchanged(helper, player, before);
            }
            api.setCurrentAmmoCount(source, 0); api.setBulletInBarrel(source, false);
            api.installAttachment(player.registryAccess(), source, attachment("extended_mag_1"));
            api.installAttachment(player.registryAccess(), destination, attachment("extended_mag_3"));
            int smallerCapacity = AttachmentDataUtils.getAmmoCountWithAttachment(source, data);
            api.setCurrentAmmoCount(destination, smallerCapacity + 1); api.setBulletInBarrel(destination, true);
            before = snapshot(player);
            helper.assertTrue(!transfer(player, 0, 1, AttachmentType.EXTENDED_MAG), "Destination lost excess rounds during magazine replacement");
            assertUnchanged(helper, player, before);
            helper.succeed();
        } finally { cleanup(player); }
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
        return GunItemBuilder.create().setId(ResourceLocation.parse("tacz:m4a1"))
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
        failures().remove(player.getUUID()); player.getInventory().clearContent(); player.discard();
    }
}
