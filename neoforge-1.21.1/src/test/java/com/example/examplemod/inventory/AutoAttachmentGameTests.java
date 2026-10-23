package com.example.examplemod.inventory;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual TaCZ pack metadata, install/unload codecs, vanilla inventory and component equality. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class AutoAttachmentGameTests {
    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void installationIsAtomicAndConservesRealAttachments(GameTestHelper helper) {
        var player = player(helper);
        try {
            ItemStack gun = gun(helper);
            IGun api = (IGun) gun.getItem();
            api.setCurrentAmmoCount(gun, 7);
            api.setBulletInBarrel(gun, true);
            player.getInventory().setItem(0, gun);
            ItemStack incoming = magazine(1);
            incoming.setCount(2);
            incoming.set(DataComponents.CUSTOM_NAME, Component.literal("real incoming components"));
            ((IAttachment) incoming.getItem()).setLaserColor(incoming, 0x123456);
            ItemStack expected = incoming.copyWithCount(1);
            helper.assertTrue(AutoAttachmentService.tryEquipPickedUp(player, incoming) == 0,
                    "Ordinary non-participant bypassed the public rule gate");
            helper.assertTrue(AutoAttachmentService.equipOne(player, 0, incoming), "Compatible empty slot did not equip");
            helper.assertTrue(incoming.getCount() == 1 && ItemStack.matches(expected,
                    api.getAttachment(player.registryAccess(), gun, AttachmentType.EXTENDED_MAG)),
                    "Installation duplicated the source stack or stripped components");
            helper.assertTrue(api.getCurrentAmmoCount(gun) == 7 && api.hasBulletInBarrel(gun),
                    "Installing an attachment changed loaded ammunition");
            var installed = gun.save(player.registryAccess()).copy();
            helper.assertTrue(!AutoAttachmentService.equipOne(player, 0, incoming)
                    && incoming.getCount() == 1 && installed.equals(gun.save(player.registryAccess())),
                    "Repeated installation consumed another item or overwrote an equal magazine");

            // A real upgrade must have room to return the old physical attachment.
            for (int slot = 3; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE));
            ItemStack upgrade = magazine(2);
            upgrade.set(DataComponents.CUSTOM_NAME, Component.literal("upgrade components"));
            helper.assertTrue(!AutoAttachmentService.equipOne(player, 0, upgrade)
                    && upgrade.getCount() == 1 && installed.equals(gun.save(player.registryAccess())),
                    "Full inventory accepted an upgrade without a return slot");
            player.getInventory().setItem(9, ItemStack.EMPTY);
            player.getInventory().setItem(10, new ItemStack(Items.STONE, 64));
            helper.assertTrue(!AutoAttachmentService.equipOne(player, 0, upgrade)
                    && upgrade.getCount() == 1 && installed.equals(gun.save(player.registryAccess())),
                    "A physically empty slot bypassed the old attachment's capacity cost");
            player.getInventory().setItem(10, new ItemStack(Items.STONE));
            helper.assertTrue(AutoAttachmentService.equipOne(player, 0, upgrade) && upgrade.isEmpty(),
                    "Upgrade did not consume exactly one incoming item");
            helper.assertTrue(ItemStack.matches(expected, player.getInventory().getItem(9)),
                    "Old attachment was not returned with its original components");
            ItemStack oldTwo = api.getAttachment(player.registryAccess(), gun, AttachmentType.EXTENDED_MAG).copy();
            helper.assertTrue(oldTwo.getCount() == 1 && oldTwo.getHoverName().getString().equals("upgrade components"),
                    "Upgraded attachment component codec changed the item");

            // An owned single item frees its own slot; a full inventory must still permit this swap.
            ItemStack third = magazine(3);
            player.getInventory().setItem(10, third);
            helper.assertTrue(AutoAttachmentService.equipOne(player, 0, third)
                    && ItemStack.matches(oldTwo, player.getInventory().getItem(10)),
                    "Owned attachment swap failed to reuse the consumed source slot");
            helper.assertTrue(api.getCurrentAmmoCount(gun) == 7 && api.hasBulletInBarrel(gun),
                    "Magazine upgrades ejected or cloned loaded ammunition");
            helper.assertTrue(!AutoAttachmentService.equipOne(player, 0, magazine(1)),
                    "Automatic equip downgraded a higher-capacity magazine");
            api.setAttachmentLock(gun, true);
            ItemStack locked = magazine(3);
            helper.assertTrue(!AutoAttachmentService.equipOne(player, 0, locked) && locked.getCount() == 1,
                    "Attachment lock was bypassed");
            api.setAttachmentLock(gun, false);
            ItemStack sight = AttachmentItemBuilder.create().setId(ResourceLocation.parse("tacz:sight_t2")).build();
            ItemStack scope = AttachmentItemBuilder.create().setId(ResourceLocation.parse("tacz:scope_elcan_4x")).build();
            helper.assertTrue(api.allowAttachment(gun, sight) && api.allowAttachment(gun, scope),
                    "Scope-preference fixtures must both be compatible with the gun");
            api.installAttachment(player.registryAccess(), gun, sight);
            player.getInventory().setItem(11, ItemStack.EMPTY);
            helper.assertTrue(!AutoAttachmentService.equipOne(player, 0, scope) && scope.getCount() == 1
                    && ItemStack.matches(sight, api.getAttachment(player.registryAccess(), gun, AttachmentType.SCOPE)),
                    "Automatic installation treated a larger zoom as an unconditional improvement");
            helper.succeed();
        } finally { cleanup(player); }
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void gunSwapMovesComponentsWithoutCloningOrDiscardingRounds(GameTestHelper helper) {
        var player = player(helper);
        try {
            ItemStack source = gun(helper), destination = gun(helper);
            source.set(DataComponents.CUSTOM_NAME, Component.literal("old gun"));
            destination.set(DataComponents.CUSTOM_NAME, Component.literal("new gun"));
            IGun api = (IGun) source.getItem();
            ItemStack attachment = magazine(2);
            attachment.set(DataComponents.CUSTOM_NAME, Component.literal("only physical magazine"));
            api.installAttachment(player.registryAccess(), source, attachment);
            api.setCurrentAmmoCount(source, 7);
            api.setBulletInBarrel(source, true);
            helper.assertTrue(AutoAttachmentService.transfer(player, source, destination) == 1,
                    "Compatible empty target slot did not receive the attachment");
            helper.assertTrue(api.getAttachment(player.registryAccess(), source, AttachmentType.EXTENDED_MAG).isEmpty()
                    && ItemStack.matches(attachment,
                        api.getAttachment(player.registryAccess(), destination, AttachmentType.EXTENDED_MAG)),
                    "Transfer copied an attachment or lost its components");
            helper.assertTrue(api.getCurrentAmmoCount(source) == 7 && api.hasBulletInBarrel(source)
                    && api.getCurrentAmmoCount(destination) == 0 && !api.hasBulletInBarrel(destination),
                    "Gun swap moved, duplicated or deleted loaded rounds");
            helper.assertTrue(source.getHoverName().getString().equals("old gun")
                    && destination.getHoverName().getString().equals("new gun"),
                    "Transfer replaced gun identity or custom components");
            helper.assertTrue(AutoAttachmentService.transfer(player, source, destination) == 0,
                    "Repeated transfer manufactured a second attachment");

            ItemStack other = magazine(1);
            api.installAttachment(player.registryAccess(), source, other);
            helper.assertTrue(AutoAttachmentService.transfer(player, source, destination) == 0,
                    "Transfer overwrote the target's existing attachment");
            api.unloadAttachment(player.registryAccess(), source, AttachmentType.EXTENDED_MAG);
            api.unloadAttachment(player.registryAccess(), destination, AttachmentType.EXTENDED_MAG);
            api.installAttachment(player.registryAccess(), source, attachment);
            int baseCapacity = TimelessAPI.getCommonGunIndex(api.getGunId(source)).orElseThrow()
                    .getGunData().getAmmoAmount();
            api.setCurrentAmmoCount(source, baseCapacity + 1);
            helper.assertTrue(AutoAttachmentService.transfer(player, source, destination) == 0
                    && api.getCurrentAmmoCount(source) == baseCapacity + 1
                    && ItemStack.matches(attachment,
                        api.getAttachment(player.registryAccess(), source, AttachmentType.EXTENDED_MAG)),
                    "Loaded magazine migrated while its source gun still required the extra capacity");
            helper.succeed();
        } finally { cleanup(player); }
    }

    private static FakePlayer player(GameTestHelper helper) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "[attachments]"));
        var origin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
        player.getInventory().clearContent();
        player.getInventory().selected = 5;
        return player;
    }

    private static ItemStack gun(GameTestHelper helper) {
        var id = ResourceLocation.parse("tacz:m4a1");
        helper.assertTrue(TimelessAPI.getCommonGunIndex(id).isPresent(), "TaCZ default m4a1 fixture was not loaded");
        return GunItemBuilder.create().setId(id).setAmmoCount(0).setAmmoInBarrel(false)
                .build(helper.getLevel().registryAccess());
    }

    private static ItemStack magazine(int level) {
        return AttachmentItemBuilder.create().setId(ResourceLocation.parse("tacz:extended_mag_" + level)).build();
    }

    private static void cleanup(FakePlayer player) {
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.discard();
    }
}
