package com.example.examplemod.backpack;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

public final class BackpackMenu extends AbstractContainerMenu {
    private final Player owner;
    private final SimpleContainer equipment = new SimpleContainer(1);
    private final SimpleContainer recovery = new SimpleContainer(MatchInventorySnapshots.RECOVERY_SLOTS);
    public BackpackMenu(int id, Inventory inventory) {
        super(BackpackRegistration.MENU.get(), id);
        owner = inventory.player;
        if (!owner.level().isClientSide) equipment.setItem(0, BackpackService.equipped(owner));
        addSlot(new Slot(equipment, 0, 80, 28) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 9; column++)
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 83 + row * 18));
        for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 8 + column * 18, 141));
        for (int i = 0; i < recovery.getContainerSize(); i++) addSlot(new Slot(recovery, i, 8 + i % 9 * 18, 181 + i / 9 * 18) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
    }
    @Override public boolean stillValid(Player player) { return player == owner && player.isAlive() && !player.isSpectator(); }
    @Override public void broadcastChanges() {
        if (!owner.level().isClientSide) equipment.setItem(0, BackpackService.equipped(owner));
        if (!owner.level().isClientSide) {
            var items = MatchInventorySnapshots.recovery(owner);
            for (int i = 0; i < recovery.getContainerSize(); i++)
                recovery.setItem(i, i < items.size() ? items.get(i).copy() : ItemStack.EMPTY);
        }
        super.broadcastChanges();
    }
    @Override public void clicked(int slot, int button, ClickType click, Player player) {
        if (slot >= 37 && slot < slots.size()) {
            if (player instanceof ServerPlayer serverPlayer && (click == ClickType.PICKUP || click == ClickType.QUICK_MOVE))
                MatchInventorySnapshots.takeRecovery(serverPlayer, slot - 37);
            return;
        }
        if (slot == 0) {
            if (!player.level().isClientSide) {
                if (click == ClickType.PICKUP) BackpackService.swapCursor(player, this);
                else if (click == ClickType.QUICK_MOVE) BackpackService.removeToInventory(player);
                broadcastChanges();
            }
            return;
        }
        super.clicked(slot, button, click, player);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index <= 0 || index >= 37) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.getItem() instanceof BackpackItem
                || stack.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem) {
            if (!player.level().isClientSide) BackpackService.equipInventory(player, slot.getContainerSlot());
            broadcastChanges();
            return ItemStack.EMPTY; // A swap must never repeat as vanilla quick-move loops can.
        }
        ItemStack old = stack.copy();
        if (index < 28 ? !moveItemStackTo(stack, 28, 37, false) : !moveItemStackTo(stack, 1, 28, false))
            return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return old;
    }
}
