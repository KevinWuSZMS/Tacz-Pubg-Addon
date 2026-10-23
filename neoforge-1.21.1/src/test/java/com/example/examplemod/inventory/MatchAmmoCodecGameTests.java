package com.example.examplemod.inventory;

import com.tacz.guns.api.item.IAmmo;
import io.netty.buffer.Unpooled;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Requires an initialized NeoForge + TaCZ test environment so the real ItemStack mixin,
 * component codecs, container persistence, and registry-aware network codec are exercised.
 * Deliberately not run by the pure checks tasks, and does not launch a game by itself.
 */
@GameTestHolder("tacz_pubg")
@PrefixGameTestTemplate(false)
public final class MatchAmmoCodecGameTests {
    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void largeAmmoRoundTrips(GameTestHelper helper) {
        var access = helper.getLevel().registryAccess();
        var ops = RegistryOps.create(NbtOps.INSTANCE, access);
        var ammoItem = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof IAmmo).findFirst().orElseThrow();
        var ammo = (IAmmo) ammoItem;
        for (int count : new int[] {64, 99, 100, 999, 4096, 8192}) {
            ItemStack original = new ItemStack(ammoItem, count);
            ammo.setAmmoId(original, ResourceLocation.fromNamespaceAndPath("tacz", "556x45"));
            CompoundTag data = original.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            data.putString("roundtrip_preserve", "unchanged");
            original.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            if (count != 8192) MatchAmmoStacks.mark(original, 999);
            var before = original.save(access).copy();
            var encoded = ItemStack.CODEC.encodeStart(ops, original).getOrThrow();
            ItemStack restored = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
            helper.assertTrue(original.getCount() == count && restored.getCount() == count, "Count lost: " + count);
            helper.assertTrue(ItemStack.isSameItemSameComponents(original, restored), "Components or ammo id changed");
            helper.assertTrue(!restored.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .contains(MatchAmmoStacks.SAVED_COUNT_KEY), "Temporary saved count leaked into inventory");
            if (count > 99) helper.assertTrue(((CompoundTag) encoded).getInt("count") == 1, "Vanilla count envelope invalid");
            helper.assertTrue(before.equals(original.save(access)), "Encoding mutated original stack");

            var inventory = NonNullList.withSize(2, ItemStack.EMPTY);
            inventory.set(0, original);
            CompoundTag container = new CompoundTag();
            ContainerHelper.saveAllItems(container, inventory, access);
            var loaded = NonNullList.withSize(2, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(container, loaded, access);
            helper.assertTrue(loaded.get(0).getCount() == count && loaded.get(1).isEmpty(), "Container roundtrip lost ammo");

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
            try {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, original);
                ItemStack synced = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
                helper.assertTrue(synced.getCount() == count
                        && ItemStack.isSameItemSameComponents(original, synced), "Network roundtrip changed large ammo");
            } finally { buffer.release(); }
        }
        ItemStack lowering = new ItemStack(ammoItem, 999);
        MatchAmmoStacks.mark(lowering, 100);
        helper.assertTrue(lowering.getMaxStackSize() == 999, "Lowering configured limit made existing count illegal");
        MatchAmmoStacks.clearMarker(lowering);
        helper.assertTrue(lowering.getCount() == 999 && !lowering.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .contains(MatchAmmoStacks.LIMIT_KEY), "Cleanup removed ammunition");
        var stone = new ItemStack(Items.STONE, 64);
        MatchAmmoStacks.mark(stone, 999);
        helper.assertTrue(stone.getMaxStackSize() == 64 && !stone.has(DataComponents.CUSTOM_DATA), "Ordinary stacking changed");
        helper.assertTrue(ItemStack.CODEC.encodeStart(ops, new ItemStack(Items.STONE, 100)).error().isPresent(),
                "Oversized ordinary items bypassed vanilla validation");
        helper.assertTrue(ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, stone).getOrThrow()).getOrThrow()
                .getCount() == 64, "Ordinary codec changed");
        var emptyData = new ItemStack(ammoItem, 500);
        emptyData.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        var emptyDataRestored = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, emptyData).getOrThrow()).getOrThrow();
        helper.assertTrue(ItemStack.isSameItemSameComponents(emptyData, emptyDataRestored)
                && emptyDataRestored.getCount() == 500, "An explicitly empty custom-data component changed");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "tacz_pubg")
    public static void ordinaryPickupAndCursorDoNotInheritMatchStacks(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ammo-test"));
        var origin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        player.setPos(origin.getX() + .5, origin.getY() + 1, origin.getZ() + .5);
        var ammo = BuiltInRegistries.ITEM.stream().filter(item -> item instanceof IAmmo).findFirst().orElseThrow();
        try {
            player.getInventory().clearContent();
            ItemStack incoming = new ItemStack(ammo, 999);
            MatchAmmoStacks.mark(incoming, 999);
            player.getInventory().add(incoming);
            int total = incoming.getCount();
            for (ItemStack stack : player.getInventory().items) if (stack.getItem() instanceof IAmmo) {
                total += stack.getCount();
                helper.assertTrue(!MatchAmmoStacks.marked(stack), "Ordinary pickup inherited round marker");
                helper.assertTrue(stack.getCount() <= stack.getMaxStackSize(), "Ordinary pickup did not split");
            }
            helper.assertTrue(total == 999, "Ordinary pickup changed total ammo");
            player.getInventory().clearContent();
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            ItemStack cursor = new ItemStack(ammo, 999);
            MatchAmmoStacks.mark(cursor, 999);
            player.containerMenu.setCarried(cursor);
            MatchAmmoStacks.normalizeOutsideRound(player);
            helper.assertTrue(!MatchAmmoStacks.marked(cursor) && cursor.getCount() <= cursor.getMaxStackSize(),
                    "Full-inventory cursor retained oversized round stack");
            int owned = cursor.getCount();
            for (var drop : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    player.getBoundingBox().inflate(6), e -> e.getItem().getItem() instanceof IAmmo)) {
                owned += drop.getItem().getCount();
                helper.assertTrue(!MatchAmmoStacks.marked(drop.getItem()), "Ordinary overflow retained round marker");
                drop.discard();
            }
            helper.assertTrue(owned == 999, "Full ordinary inventory lost overflow ammunition");
            helper.succeed();
        } finally {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.getInventory().clearContent();
            player.discard();
        }
    }
}
