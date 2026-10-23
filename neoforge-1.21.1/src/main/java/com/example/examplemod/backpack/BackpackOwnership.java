package com.example.examplemod.backpack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

/** Temporary inputs are still carried property; world storage and recipe outputs are not. */
public final class BackpackOwnership {
    private BackpackOwnership() {}

    public static boolean isTemporaryInput(AbstractContainerMenu menu, Slot slot) {
        if (slot.container instanceof CraftingContainer) return true;
        int index = menu.slots.indexOf(slot);
        return index >= 0 && index < returnedInputCount(menu);
    }

    public static List<Slot> temporaryInputs(Player player) {
        List<Slot> result = new ArrayList<>();
        IdentityHashMap<Container, Set<Integer>> counted = new IdentityHashMap<>();
        for (var menu : List.of(player.containerMenu, player.inventoryMenu)) {
            for (Slot slot : menu.slots) {
                if (isTemporaryInput(menu, slot)
                        && counted.computeIfAbsent(slot.container, ignored -> new HashSet<>()).add(slot.getContainerSlot()))
                    result.add(slot);
            }
        }
        return result;
    }

    /** Identity is deliberate: a recipe output equal to an input must not become a free acquisition. */
    public static boolean isTemporaryInputStack(Player player, ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Slot slot : temporaryInputs(player)) if (slot.getItem() == stack) return true;
        return false;
    }

    private static int returnedInputCount(AbstractContainerMenu menu) {
        if (menu instanceof ItemCombinerMenu combiner) return combiner.getResultSlot();
        if (menu instanceof LoomMenu) return 3;
        if (menu instanceof EnchantmentMenu || menu instanceof GrindstoneMenu
                || menu instanceof CartographyTableMenu || menu instanceof MerchantMenu) return 2;
        if (menu instanceof StonecutterMenu || menu instanceof BeaconMenu) return 1;
        return 0;
    }
}
