package com.example.examplemod.throwables;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real item/use/container transitions; needs the isolated initialized mod GameTest environment. */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class ThrowableLifecycleGameTests {
    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    @SuppressWarnings("unchecked")
    public static void cookedGrenadeSurvivesInventoryTransferExactlyOnce(GameTestHelper helper) throws Exception {
        // Keep this synchronous FakePlayer fixture: a real server tick would remove an
        // unconnected player's held state. Advance only the test's ordinary-use clock.
        var clockField = ThrowableService.class.getDeclaredField("CLOCKS");
        clockField.setAccessible(true);
        var clocks = (java.util.Map<net.minecraft.server.MinecraftServer, Long>) clockField.get(null);
        var server = helper.getLevel().getServer();
        Long previousClock = clocks.get(server);
        var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "throwable-test"));
        var origin = helper.absolutePos(BlockPos.ZERO);
        player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
        player.getAbilities().instabuild = false;
        try {
            var item = ThrowableRegistration.ITEMS.get(ThrowableKind.FRAG).get();
            var stack = new ItemStack(item, 2);
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            helper.assertTrue(ThrowableService.begin(player, item, InteractionHand.MAIN_HAND), "Could not prepare grenade");
            player.startUsingItem(InteractionHand.MAIN_HAND);
            var profile = ThrowableSettingsService.profile(ThrowableKind.FRAG);
            if (profile.prepareTicks() > 0) {
                ThrowableService.cook(player);
                helper.assertTrue(stack.getCount() == 2 && !ThrowableService.cooking(player),
                        "Cooking bypassed the preparation delay");
            }
            clocks.put(server, clocks.getOrDefault(server, 0L) + profile.prepareTicks());
            ThrowableService.cook(player);
            helper.assertTrue(stack.getCount() == 1 && ThrowableService.cooking(player), "Cooking did not escrow exactly one");
            ThrowableService.cook(player);
            helper.assertTrue(stack.getCount() == 1, "Repeated cook packet consumed twice");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.containerMenu.setCarried(stack);
            player.stopUsingItem();
            ThrowableService.release(player, false);
            helper.assertTrue(ThrowableService.cooking(player), "Empty vanilla use extinguished cooked grenade");
            ThrowableService.throwCooked(player);
            ThrowableService.throwCooked(player);
            ThrowableService.cancel(player);
            int live = 0;
            for (var grenade : helper.getLevel().getEntitiesOfClass(PubgThrowableEntity.class,
                    player.getBoundingBox().inflate(6), e -> e.getOwner() == player)) {
                live++;
                helper.assertTrue(grenade.kind() == ThrowableKind.FRAG && grenade.remainingFuse() == profile.cookedFuseTicks(),
                        "Committed grenade identity or fuse changed");
                grenade.discard();
            }
            helper.assertTrue(live == 1 && stack.getCount() == 1 && !ThrowableService.cooking(player),
                    "Moved grenade was lost, duplicated, or consumed twice");
            helper.succeed();
        } finally {
            ThrowableService.cancel(player);
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.getInventory().clearContent();
            player.discard();
            if (previousClock == null) clocks.remove(server); else clocks.put(server, previousClock);
        }
    }
}
