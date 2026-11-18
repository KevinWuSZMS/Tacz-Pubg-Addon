package com.example.examplemod.inventory;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.util.AttachmentDataUtils;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
        return player;
    }
    private static void cleanup(FakePlayer player) throws Exception {
        failures().remove(player.getUUID()); player.getInventory().clearContent(); player.discard();
    }
}
