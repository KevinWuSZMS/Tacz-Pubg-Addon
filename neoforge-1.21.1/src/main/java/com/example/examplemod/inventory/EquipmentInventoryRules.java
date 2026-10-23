package com.example.examplemod.inventory;

import com.example.examplemod.armor.PubgArmorItem;
import com.example.examplemod.backpack.BackpackItem;
import com.example.examplemod.backpack.BackpackOwnership;
import com.example.examplemod.backpack.BackpackRules;
import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.zones.match.MatchService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Counts equipment by category, independent of tier, damage, and where it is carried. */
public final class EquipmentInventoryRules {
    public enum Category {
        HELMET(EquipmentSlot.HEAD), VEST(EquipmentSlot.CHEST), BACKPACK(EquipmentSlot.LEGS);
        public final EquipmentSlot slot;
        Category(EquipmentSlot slot) { this.slot = slot; }
    }
    private EquipmentInventoryRules() {}
    public static Category category(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof BackpackItem
                || stack.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem) return Category.BACKPACK;
        if (stack.getItem() instanceof ArmorItem armor) {
            if (armor.getEquipmentSlot() == EquipmentSlot.HEAD) return Category.HELMET;
            if (armor.getEquipmentSlot() == EquipmentSlot.CHEST) return Category.VEST;
        }
        return null;
    }
    public static int limit(Player player) {
        if (!InventoryConfig.active(player)) return 0;
        if (player instanceof ServerPlayer serverPlayer) {
            var game = MatchService.participantGame(serverPlayer.server, player.getUUID());
            return game == null ? 0 : game.settings().equipmentPerCategoryLimit();
        }
        return InventoryConfig.clientEquipmentLimit();
    }
    public static int[] counts(ItemStack stack) { return counts(stack, 0); }
    private static int[] counts(ItemStack stack, int depth) {
        int[] result = new int[3];
        if (stack.isEmpty()) return result;
        if (depth >= 8) return new int[]{1_000_000, 1_000_000, 1_000_000};
        Category category = category(stack);
        if (category != null) result[category.ordinal()] = 1;
        for (ItemStack item : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems())
            add(result, counts(item, depth + 1), 1);
        for (ItemStack item : stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).items())
            add(result, counts(item, depth + 1), 1);
        for (int i = 0; i < 3; i++) result[i] = (int) Math.min(1_000_000L, (long) result[i] * stack.getCount());
        return result;
    }
    public static int[] carried(Player player) {
        int[] result = new int[3];
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) add(result, counts(player.getInventory().getItem(i)), 1);
        add(result, counts(player.containerMenu.getCarried()), 1);
        for (Slot slot : BackpackOwnership.temporaryInputs(player)) add(result, counts(slot.getItem()), 1);
        return result;
    }
    private static void add(int[] target, int[] values, int sign) {
        for (int i = 0; i < 3; i++) target[i] = (int) Math.clamp((long) target[i] + (long) sign * values[i], 0, 1_000_000);
    }
    public static int accepted(int limit, int[] held, int[] unit, int requested) {
        if (limit == 0) return requested;
        int accepted = requested;
        for (int i = 0; i < 3; i++) if (unit[i] > 0)
            accepted = Math.min(accepted, Math.max(0, limit - held[i]) / unit[i]);
        return accepted;
    }
    public static int allowed(Player player, ItemStack incoming) {
        int limit = limit(player);
        return limit == 0 || incoming.isEmpty() ? incoming.getCount()
                : accepted(limit, carried(player), counts(incoming.copyWithCount(1)), incoming.getCount());
    }
    public static boolean permitsChange(Player player, ItemStack incoming, ItemStack outgoing) {
        int limit = limit(player);
        if (limit == 0) return true;
        int[] before = carried(player), after = before.clone();
        add(after, counts(outgoing), -1);
        add(after, counts(incoming), 1);
        for (int i = 0; i < 3; i++) if (after[i] > Math.max(limit, before[i])) return false;
        return true;
    }
    public static void warn(Player player) {
        player.displayClientMessage(Component.translatable("message.tacz_pubg.equipment.limit_reached", limit(player)), true);
    }
    public static boolean permitsClick(AbstractContainerMenu menu, int index, int button, ClickType type, Player player) {
        if (limit(player) == 0 || index < 0 || index >= menu.slots.size()) return true;
        Slot slot = menu.slots.get(index);
        if (type == ClickType.PICKUP_ALL) {
            ItemStack cursor = menu.getCarried();
            if (cursor.isEmpty()) return true;
            int remaining = Math.max(0, cursor.getMaxStackSize() - cursor.getCount());
            int[] extra = new int[3];
            for (Slot candidate : menu.slots) {
                if (owned(menu, candidate, player) || !candidate.mayPickup(player)
                        || !menu.canTakeItemForPickAll(cursor, candidate)
                        || !ItemStack.isSameItemSameComponents(cursor, candidate.getItem())) continue;
                int amount = Math.min(remaining, candidate.getItem().getCount());
                add(extra, counts(cursor.copyWithCount(amount)), 1);
                remaining -= amount;
            }
            int[] held = carried(player);
            for (int i = 0; i < 3; i++) if (extra[i] > 0 && held[i] + extra[i] > Math.max(limit(player), held[i])) return reject(menu, player);
            return true;
        }
        if (owned(menu, slot, player) || slot.getItem().isEmpty()) return true;
        ItemStack incoming = slot.getItem(), outgoing = ItemStack.EMPTY;
        if (type == ClickType.PICKUP) {
            ItemStack cursor = menu.getCarried();
            if (cursor.isEmpty()) incoming = incoming.copyWithCount(button == 1 ? (incoming.getCount() + 1) / 2 : incoming.getCount());
            else if (!ItemStack.isSameItemSameComponents(cursor, incoming)) {
                // Bundles can consume the clicked item instead of swapping, so never credit their departure.
                if (!cursor.has(DataComponents.BUNDLE_CONTENTS) && !incoming.has(DataComponents.BUNDLE_CONTENTS)) outgoing = cursor;
            } else if (slot.mayPlace(cursor)) return true;
            else incoming = incoming.copyWithCount(Math.min(incoming.getCount(), Math.max(0, cursor.getMaxStackSize() - cursor.getCount())));
        } else if (type == ClickType.SWAP && button >= 0 && (button < 9 || button == 40)) {
            ItemStack held = player.getInventory().getItem(button);
            outgoing = held.copyWithCount(Math.min(held.getCount(), slot.getMaxStackSize(held)));
        } else return true; // Shift acquisition is checked where it actually enters the inventory.
        return permitsChange(player, incoming, outgoing) || reject(menu, player);
    }
    private static boolean owned(AbstractContainerMenu menu, Slot slot, Player player) {
        return slot.container == player.getInventory() || BackpackOwnership.isTemporaryInput(menu, slot);
    }
    private static boolean reject(AbstractContainerMenu menu, Player player) {
        warn(player); menu.sendAllDataToRemote(); return false;
    }
    /** Shared preflight for explicit F swaps and later opt-in automatic upgrades. */
    public static boolean mayReplace(Player player, ItemStack incoming, boolean automatic) {
        Category category = category(incoming);
        if (category == null || AutoAttachmentService.busy(player)) return false;
        ItemStack old = player.getItemBySlot(category.slot);
        if (!player.isCreative() && EnchantmentHelper.has(old, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) return false;
        if (!permitsChange(player, incoming.copyWithCount(1), old)) return false;
        if (automatic && !better(incoming, old)) return false;
        if (BackpackService.active(player) && category == Category.BACKPACK) {
            int next = BackpackRules.capacity(incoming.getItem() instanceof BackpackItem pack ? pack.tier() : 0,
                    player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem);
            if (next < BackpackService.capacity(player) && BackpackService.used(player) > next) return false;
        }
        return true;
    }
    public static boolean better(ItemStack incoming, ItemStack old) {
        if (incoming.getItem() instanceof PubgArmorItem && PubgArmorItem.health(incoming) <= 0) return false;
        if (old.isEmpty()) return true;
        if (incoming.getItem() instanceof BackpackItem next)
            return old.getItem() instanceof BackpackItem previous && next.tier() > previous.tier();
        if (incoming.getItem() instanceof PubgArmorItem next && old.getItem() instanceof PubgArmorItem previous) {
            return EquipmentPreference.betterArmor(next.helmet(), next.tier(), PubgArmorItem.health(incoming),
                    previous.tier(), PubgArmorItem.health(old));
        }
        return incoming.getItem() == old.getItem() && incoming.getDamageValue() < old.getDamageValue();
    }
}
