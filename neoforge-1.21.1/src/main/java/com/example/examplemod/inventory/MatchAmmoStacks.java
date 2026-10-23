package com.example.examplemod.inventory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.tacz.guns.api.item.IAmmo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Match-only stacking metadata, with a lossless disk representation through vanilla's count <= 99 codec.
 * The temporary saved count exists only in the encoded copy, never in the live inventory.
 */
public final class MatchAmmoStacks {
    public static final String LIMIT_KEY = "tacz_pubg_round_ammo_limit";
    public static final String SAVED_COUNT_KEY = "tacz_pubg_saved_ammo_count";
    private static final String SAVED_EMPTY_DATA_KEY = "tacz_pubg_saved_ammo_empty_data";
    private static final int MAX_LIMIT = 4096;

    private MatchAmmoStacks() {}

    public static void mark(ItemStack stack, int limit) {
        if (stack.isEmpty() || !(stack.getItem() instanceof IAmmo)) return;
        int bounded = Math.clamp(limit, 1, MAX_LIMIT);
        CompoundTag data = data(stack);
        if (data.getInt(LIMIT_KEY) == bounded) return;
        data.putInt(LIMIT_KEY, bounded);
        setData(stack, data);
    }

    public static void clearMarker(ItemStack stack) {
        if (!(stack.getItem() instanceof IAmmo)) return;
        CompoundTag data = data(stack);
        if (!data.contains(LIMIT_KEY)) return;
        data.remove(LIMIT_KEY);
        setData(stack, data);
    }

    public static boolean marked(ItemStack stack) {
        return stack.getItem() instanceof IAmmo
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).contains(LIMIT_KEY);
    }

    /**
     * Container clicks can bypass Inventory.add. Repair every owned stack and the cursor without
     * deleting overflow; a full ordinary inventory places its actual remainder on the ground.
     */
    public static void normalizeOutsideRound(Player player) {
        if (InventoryConfig.active(player) || player.level().isClientSide) return;
        var inventory = player.getInventory();
        boolean changed = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (marked(stack)) { clearMarker(stack); changed = true; }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (marked(carried)) { clearMarker(carried); changed = true; }
        for (int i = 0; i <= inventory.getContainerSize(); i++) {
            ItemStack stack = i == inventory.getContainerSize() ? carried : inventory.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof IAmmo)) continue;
            int limit = ordinaryLimit(stack);
            if (stack.getCount() <= limit) continue;
            ItemStack extra = stack.copyWithCount(stack.getCount() - limit);
            stack.setCount(limit);
            for (int pass = 0; pass < 2 && !extra.isEmpty(); pass++) {
                for (int destination = 0; destination < inventory.items.size() && !extra.isEmpty(); destination++) {
                    ItemStack target = inventory.getItem(destination);
                    if (target.isEmpty() != (pass == 1) || target == stack) continue;
                    if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, extra)) continue;
                    int accepted = Math.min(extra.getCount(), Math.max(0, limit - target.getCount()));
                    if (accepted == 0) continue;
                    if (target.isEmpty()) inventory.setItem(destination, extra.split(accepted));
                    else { target.grow(accepted); extra.shrink(accepted); }
                }
            }
            if (!extra.isEmpty()) {
                // Keep one genuine remainder if storage is full, even for old counts above 4096.
                // The ammo CODEC saves it losslessly and the next ordinary pickup splits it.
                var dropped = player.drop(extra.copy(), false);
                if (dropped == null) stack.grow(extra.getCount());
            }
            changed = true;
        }
        if (changed) {
            inventory.setChanged();
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.inventoryMenu.broadcastChanges();
                if (serverPlayer.containerMenu != serverPlayer.inventoryMenu) serverPlayer.containerMenu.broadcastChanges();
            }
        }
    }

    private static int ordinaryLimit(ItemStack stack) {
        return Math.max(1, Math.min(99, stack.getItem().getMaxStackSize(stack)));
    }

    public static int maximum(ItemStack stack, int ordinary) {
        if (!(stack.getItem() instanceof IAmmo)) return ordinary;
        // Read only: never mutate CustomData's backing tag.
        int limit = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe().getInt(LIMIT_KEY);
        return limit > 0 ? Math.max(stack.getCount(), Math.clamp(limit, 1, MAX_LIMIT)) : ordinary;
    }

    public static Codec<ItemStack> wrapCodec(Codec<ItemStack> vanilla) {
        return vanilla.flatXmap(MatchAmmoStacks::restoreSavedCount, MatchAmmoStacks::prepareSavedCount);
    }

    private static DataResult<ItemStack> prepareSavedCount(ItemStack stack) {
        if (!(stack.getItem() instanceof IAmmo) || stack.getCount() <= 99) return DataResult.success(stack);
        ItemStack encoded = stack.copyWithCount(1);
        CompoundTag data = data(encoded);
        if (data.isEmpty() && stack.has(DataComponents.CUSTOM_DATA)) data.putBoolean(SAVED_EMPTY_DATA_KEY, true);
        data.putInt(SAVED_COUNT_KEY, stack.getCount());
        setData(encoded, data);
        return DataResult.success(encoded);
    }

    private static DataResult<ItemStack> restoreSavedCount(ItemStack stack) {
        if (!(stack.getItem() instanceof IAmmo)) return DataResult.success(stack);
        CompoundTag data = data(stack);
        if (!data.contains(SAVED_COUNT_KEY)) return DataResult.success(stack);
        if (!data.contains(SAVED_COUNT_KEY, Tag.TAG_INT) || data.getInt(SAVED_COUNT_KEY) < 100)
            return DataResult.error(() -> "Invalid stored PUBG ammunition count");
        if (stack.getCount() != 1)
            return DataResult.error(() -> "Stored PUBG ammunition count requires a single-item envelope");
        ItemStack restored = stack.copyWithCount(data.getInt(SAVED_COUNT_KEY));
        boolean hadEmptyData = data.getBoolean(SAVED_EMPTY_DATA_KEY);
        data.remove(SAVED_COUNT_KEY);
        data.remove(SAVED_EMPTY_DATA_KEY);
        if (data.isEmpty() && hadEmptyData) restored.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        else setData(restored, data);
        return DataResult.success(restored);
    }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void setData(ItemStack stack, CompoundTag data) {
        if (data.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }
}
