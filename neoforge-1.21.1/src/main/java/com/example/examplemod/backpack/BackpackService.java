package com.example.examplemod.backpack;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Backpacks occupy the vanilla leggings slot; every carried item remains in the vanilla inventory. */
public final class BackpackService {
    private static final Map<UUID, BackpackPayloads.State> LAST_SENT = new HashMap<>();
    private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();
    private BackpackService() {}

    public static void register(IEventBus bus) {
        bus.addListener(BackpackService::tick);
        bus.addListener(BackpackService::login);
        bus.addListener(BackpackService::logout);
        bus.addListener(BackpackService::tracking);
    }

    public static ItemStack equipped(Player player) {
        return player.getItemBySlot(EquipmentSlot.LEGS);
    }
    public static int tier(Player player) {
        return equipped(player).getItem() instanceof BackpackItem pack ? pack.tier() : 0;
    }
    static void setEquipped(Player player, ItemStack stack) {
        player.setItemSlot(EquipmentSlot.LEGS, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        player.getInventory().setChanged();
        if (player instanceof ServerPlayer serverPlayer) sync(serverPlayer, true);
    }
    public static boolean active(Player player) {
        return player instanceof ServerPlayer
                && com.example.examplemod.inventory.InventoryConfig.active(player);
    }
    private static boolean vest(Player player) {
        // The flight pack is independent and deliberately grants no armor capacity.
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof ArmorItem || com.example.examplemod.armor.PubgArmorItem.vest(chest);
    }
    public static int capacity(Player player) { return BackpackRules.capacity(tier(player), vest(player)); }
    public static int used(Player player) {
        int result = 0;
        var settings = com.example.examplemod.inventory.InventoryConfig.current(player);
        for (int index = 0; index < player.getInventory().items.size(); index++) {
            ItemStack stack = player.getInventory().items.get(index);
            boolean equippedMelee = index < 9 && settings.hotbar().get(index).equals("melee")
                    && com.example.examplemod.inventory.MatchInventoryService.role(stack).equals("melee")
                    && !settings.capacityOverrides().containsKey(com.example.examplemod.inventory.InventoryConfig.key(stack));
            if (!equippedMelee) result = BackpackRules.add(result, BackpackWeights.weight(player, stack));
        }
        for (ItemStack stack : player.getInventory().offhand) result = BackpackRules.add(result, BackpackWeights.weight(player, stack));
        // Cursor storage cannot be used as a weightless extra inventory.
        result = BackpackRules.add(result, BackpackWeights.weight(player, player.containerMenu.getCarried()));
        // Inputs returned on close still count, including the hidden personal crafting grid.
        for (Slot slot : BackpackOwnership.temporaryInputs(player))
            result = BackpackRules.add(result, BackpackWeights.weight(player, slot.getItem()));
        return result;
    }
    public static int allowed(Player player, ItemStack stack) {
        return active(player) ? BackpackRules.accepted(used(player), capacity(player), BackpackWeights.unit(player, stack), stack.getCount())
                : stack.getCount();
    }
    public static void warn(Player player) {
        if (!(player instanceof ServerPlayer)) return;
        long tick = player.level().getGameTime();
        Long previous = LAST_WARNING.get(player.getUUID());
        if (previous == null || tick - previous >= 20) {
            LAST_WARNING.put(player.getUUID(), tick);
            player.displayClientMessage(Component.translatable("message.tacz_pubg.backpack.full"), true);
        }
    }

    /** The replacement is already carried; an old pack that cannot fit can be dropped during a swap. */
    static boolean maySwap(Player player, ItemStack replacement) {
        if (!replacement.isEmpty() && (replacement.getCount() != 1
                || player.getEquipmentSlotForItem(replacement) != EquipmentSlot.LEGS)) return false;
        if (!player.isCreative() && net.minecraft.world.item.enchantment.EnchantmentHelper.has(
                equipped(player), net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) return false;
        if (!active(player)) return true;
        int targetTier = replacement.getItem() instanceof BackpackItem item ? item.tier() : 0;
        // A downgrade must leave room for every item that remains carried.
        if (!BackpackRules.permitsEquipmentChange(used(player), BackpackWeights.weight(player, replacement),
                replacement.isEmpty() ? BackpackWeights.weight(player, equipped(player)) : 0,
                BackpackRules.capacity(targetTier, vest(player)))) {
            player.displayClientMessage(Component.translatable("message.tacz_pubg.backpack.cannot_remove"), true);
            return false;
        }
        return true;
    }
    private static boolean dropPrevious(Player player, ItemStack replacement) {
        if (!active(player) || replacement.isEmpty() || equipped(player).isEmpty()) return false;
        int targetTier = replacement.getItem() instanceof BackpackItem item ? item.tier() : 0;
        return !BackpackRules.permitsEquipmentChange(used(player), BackpackWeights.weight(player, replacement),
                BackpackWeights.weight(player, equipped(player)), BackpackRules.capacity(targetTier, vest(player)));
    }
    /** Spawn first so a canceled drop leaves both physical items untouched. */
    private static void swap(Player player, ItemStack replacement, java.util.function.Consumer<ItemStack> source) {
        if (!maySwap(player, replacement)) return;
        ItemStack previous = equipped(player);
        net.minecraft.world.entity.item.ItemEntity dropped = null;
        try {
            if (dropPrevious(player, replacement)) {
                dropped = new net.minecraft.world.entity.item.ItemEntity(player.level(), player.getX(),
                        player.getY() + .25, player.getZ(), previous.copy());
                dropped.setDefaultPickUpDelay();
                if (!player.level().addFreshEntity(dropped)) { dropped.discard(); return; }
            }
            source.accept(dropped == null ? previous : ItemStack.EMPTY);
            setEquipped(player, replacement);
        } catch (RuntimeException failure) {
            if (dropped != null) dropped.discard();
            source.accept(replacement);
            player.setItemSlot(EquipmentSlot.LEGS, previous);
            com.example.examplemod.ExampleMod.LOGGER.error("Backpack swap failed; both items retained", failure);
        }
    }
    public static void equipHand(Player player, InteractionHand hand) {
        ItemStack replacement = player.getItemInHand(hand);
        if (!(replacement.getItem() instanceof BackpackItem
                || replacement.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem)) return;
        swap(player, replacement, returned -> player.setItemInHand(hand, returned));
        player.containerMenu.broadcastChanges();
    }
    static void swapCursor(Player player, AbstractContainerMenu menu) {
        ItemStack replacement = menu.getCarried();
        swap(player, replacement, menu::setCarried);
    }
    static void equipInventory(Player player, int inventorySlot) {
        ItemStack replacement = player.getInventory().getItem(inventorySlot);
        if (!(replacement.getItem() instanceof BackpackItem
                || replacement.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem)) return;
        swap(player, replacement, returned -> player.getInventory().setItem(inventorySlot, returned));
    }
    static void removeToInventory(Player player) {
        if (!maySwap(player, ItemStack.EMPTY)) return;
        int empty = -1;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            if (player.getInventory().getItem(i).isEmpty()
                    && !com.example.examplemod.inventory.MatchInventoryService.locked(player, i)) {
                empty = i;
                break;
            }
        }
        if (empty < 0) { warn(player); return; }
        ItemStack old = equipped(player);
        setEquipped(player, ItemStack.EMPTY);
        player.getInventory().setItem(empty, old);
    }
    public static void open(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new BackpackMenu(id, inventory),
                Component.translatable("container.tacz_pubg.backpack")));
        sync(player, true);
    }

    /**
     * Reject external transfers before vanilla mutates either source or destination.
     * Deliberately do not roll back crafting or transaction callbacks after execution.
     */
    public static boolean permitsClick(AbstractContainerMenu menu, int slotId, int button, ClickType type, Player player) {
        if (!active(player) || slotId < 0 || slotId >= menu.slots.size()) return true;
        Slot slot = menu.getSlot(slotId);
        // Automatic equipment pickup is a direct wear/drop transaction. Its shared inventory
        // router checks capacity only for items that actually remain in storage.
        if (slot.container != player.getInventory()
                && com.example.examplemod.inventory.EquipmentInventoryRules.category(slot.getItem()) != null
                && com.example.examplemod.inventory.InventoryConfig.current(player).autoEquipmentReplacement()
                && slot.mayPickup(player)
                && (type == ClickType.PICKUP && menu.getCarried().isEmpty()
                    || type == ClickType.SWAP && button >= 0 && button < 9
                        && com.example.examplemod.inventory.EquipmentInventoryRules.mayReplace(player, slot.getItem(), true)))
            return true;
        if (slot.container == player.getInventory() && slot.getContainerSlot() == 37 && tier(player) > 0) {
            ItemStack replacement = switch (type) {
                case PICKUP -> menu.getCarried();
                case SWAP -> button >= 0 && (button < 9 || button == 40) ? player.getInventory().getItem(button) : ItemStack.EMPTY;
                default -> ItemStack.EMPTY;
            };
            if (type == ClickType.PICKUP || type == ClickType.SWAP || type == ClickType.QUICK_MOVE) {
                if (!maySwap(player, replacement)) { menu.sendAllDataToRemote(); return false; }
                if (dropPrevious(player, replacement)) {
                    if (type == ClickType.PICKUP) swapCursor(player, menu);
                    else if (type == ClickType.SWAP) equipInventory(player, button);
                    menu.sendAllDataToRemote();
                    return false;
                }
            } else if (type == ClickType.THROW && used(player) > BackpackRules.capacity(0, vest(player))) {
                warn(player); menu.sendAllDataToRemote(); return false;
            }
        }
        if (((slot.container == player.getInventory() || BackpackOwnership.isTemporaryInput(menu, slot)) && type != ClickType.PICKUP_ALL)
                || menu instanceof BackpackMenu && slotId == 0) return true;
        ItemStack incoming = slot.getItem(), cursor = menu.getCarried();
        if (incoming.isEmpty() && type != ClickType.PICKUP_ALL) return true;
        int extra = 0;
        switch (type) {
            case QUICK_MOVE -> { } // moveItemStackTo enforces capacity per insertion, including repeated crafting.
            case PICKUP -> {
                if (cursor.isEmpty()) {
                    int count = button == 1 ? (incoming.getCount() + 1) / 2 : incoming.getCount();
                    extra = BackpackWeights.weight(player, incoming.copyWithCount(count));
                } else if (!ItemStack.isSameItemSameComponents(cursor, incoming)) {
                    // A right-clicked cursor bundle absorbs the source instead of leaving the
                    // player. Crediting the bundle's departure would allow unlimited weight.
                    boolean bundleAbsorbs = button == 1 && cursor.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
                    extra = BackpackWeights.weight(player, incoming)
                            - (bundleAbsorbs ? 0 : BackpackWeights.weight(player, cursor));
                } else if (!slot.mayPlace(cursor)) {
                    int count = Math.min(incoming.getCount(), cursor.getMaxStackSize() - cursor.getCount());
                    extra = BackpackWeights.weight(player, incoming.copyWithCount(count));
                }
            }
            case SWAP -> {
                if (button >= 0 && (button < 9 || button == 40)) {
                    ItemStack outgoing = player.getInventory().getItem(button);
                    int removed = Math.min(outgoing.getCount(), slot.getMaxStackSize(outgoing));
                    extra = BackpackWeights.weight(player, incoming) - BackpackWeights.weight(player, outgoing.copyWithCount(removed));
                }
            }
            case PICKUP_ALL -> {
                if (!cursor.isEmpty()) {
                    int remaining = cursor.getMaxStackSize() - cursor.getCount();
                    for (Slot candidate : menu.slots) {
                        if (candidate.container != player.getInventory() && !BackpackOwnership.isTemporaryInput(menu, candidate)
                                && candidate.mayPickup(player)
                                && ItemStack.isSameItemSameComponents(candidate.getItem(), cursor)) {
                            int count = Math.min(remaining, candidate.getItem().getCount());
                            extra = BackpackRules.add(extra, BackpackWeights.weight(player, cursor.copyWithCount(count)));
                            remaining -= count;
                        }
                    }
                }
            }
            default -> { } // Dropping and distributing already-carried items are always allowed.
        }
        if (extra > 0 && !BackpackRules.permitsChange(used(player), BackpackRules.add(used(player), extra), capacity(player))) {
            warn(player);
            menu.sendAllDataToRemote();
            return false;
        }
        return true;
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 5 == 0) sync(player, false);
    }
    private static BackpackPayloads.State state(ServerPlayer player) {
        return new BackpackPayloads.State(player.getUUID(), tier(player), used(player), capacity(player), active(player));
    }
    private static void sync(ServerPlayer player, boolean force) {
        var current = state(player);
        var previous = LAST_SENT.put(player.getUUID(), current);
        if (force || previous == null || previous.tier() != current.tier()) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, current);
        } else if (!current.equals(previous)) PacketDistributor.sendToPlayer(player, current);
    }
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player, true);
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
        LAST_WARNING.remove(event.getEntity().getUUID());
    }
    private static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer watcher && event.getTarget() instanceof ServerPlayer subject)
            PacketDistributor.sendToPlayer(watcher, state(subject));
    }
}
