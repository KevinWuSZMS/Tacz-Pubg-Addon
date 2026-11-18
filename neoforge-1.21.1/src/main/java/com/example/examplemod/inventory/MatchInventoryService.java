package com.example.examplemod.inventory;

import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.throwables.ThrowablePayloads;
import com.example.examplemod.vitals.PubgMedicineItem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Authoritative acquisition and placement. Existing inventory items are moved, never recreated. */
public final class MatchInventoryService {
    private record Sent(boolean active, InventorySettings settings, int equipmentLimit) {}
    private record TemporaryUse(int source, int target, int previousSelected, ItemStack displaced) {}
    private static final Map<UUID, Sent> SENT = new HashMap<>();
    private static final Map<UUID, TemporaryUse> TEMPORARY = new HashMap<>();
    private static long revision;
    private MatchInventoryService() {}
    public static void register(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(InventoryPayloads::register);
        gameBus.addListener(MatchInventoryService::tick);
        gameBus.addListener((LivingSwapItemsEvent.Hands e) -> {
            if (e.getEntity() instanceof Player p && InventoryConfig.active(p)) e.setCanceled(true);
        });
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            SENT.remove(e.getEntity().getUUID()); TEMPORARY.remove(e.getEntity().getUUID());
        });
        gameBus.addListener((ServerStoppedEvent e) -> { SENT.clear(); TEMPORARY.clear(); revision = 0; });
        ThrowablePayloads.setQuickActionHandler(MatchInventoryService::selectAndUse);
    }
    public static boolean weapon(ItemStack stack) {
        return stack.getItem() instanceof IGun || stack.getItem() instanceof CrossbowItem;
    }
    public static String role(ItemStack stack) {
        if (stack.isEmpty()) return "free";
        if (stack.getItem() instanceof IGun gun) {
            String type = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).map(index -> index.getType()).orElse("");
            return type.equalsIgnoreCase("pistol") || type.equalsIgnoreCase("handgun") ? "handgun" : "long_gun";
        }
        if (stack.getItem() instanceof CrossbowItem) return "long_gun";
        if (stack.getItem() instanceof me.xjqsh.lrtactical.api.item.IMeleeWeapon) return "melee";
        if (stack.getItem() instanceof com.example.examplemod.throwables.PubgThrowableItem
                || stack.getItem() instanceof me.xjqsh.lrtactical.api.item.IThrowable) return "throwable";
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return switch (id) {
            case "tacz_pubg:med_kit" -> "med_kit";
            case "tacz_pubg:first_aid_kit" -> "first_aid_kit";
            case "tacz_pubg:bandage" -> "bandage";
            case "tacz_pubg:energy_drink", "tacz_pubg:painkiller", "tacz_pubg:adrenaline_syringe" -> "boost";
            default -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) ? "melee" : "free";
        };
    }
    public static boolean locked(Player player, int inventorySlot) {
        return InventoryConfig.active(player) && inventorySlot >= 0 && inventorySlot < 9
                && !InventoryConfig.current(player).hotbar().get(inventorySlot).equals("free");
    }
    public static boolean mayPlace(Slot slot, ItemStack stack) {
        if (!(slot.container instanceof Inventory inv) || !InventoryConfig.active(inv.player)) return true;
        int index = slot.getContainerSlot();
        if (index == 40 || locked(inv.player, index)) return false;
        return !weapon(stack) || index >= 36;
    }
    public static void prepareStack(Player player, ItemStack stack) {
        if (stack.getItem() instanceof IAmmo) {
            if (InventoryConfig.active(player)) MatchAmmoStacks.mark(stack, InventoryConfig.ammoStackLimit(player, stack));
            else MatchAmmoStacks.clearMarker(stack);
        }
    }
    /** Inventory.add callers retain the actual unaccepted remainder in their original stack. */
    public static boolean insert(Player player, ItemStack incoming, boolean acquisition) {
        ItemStack acquiredType = acquisition ? incoming.copyWithCount(1) : ItemStack.EMPTY;
        int previousCount = incoming.getCount();
        try {
            return insertInternal(player, incoming, acquisition);
        } finally {
            if (acquisition && incoming.getCount() < previousCount) PubgInventoryService.acquired(player, acquiredType);
        }
    }
    private static boolean insertInternal(Player player, ItemStack incoming, boolean acquisition) {
        if (incoming.isEmpty()) return false;
        if (acquisition) AutoAttachmentService.tryEquipPickedUp(player, incoming);
        if (incoming.isEmpty()) return true;
        if (acquisition && EquipmentPickupTransfers.automatic(player, incoming) && incoming.isEmpty()) return true;
        prepareStack(player, incoming);
        var inv = player.getInventory();
        var rules = InventoryConfig.current(player);
        String role = role(incoming);
        int allowed = acquisition ? BackpackService.allowed(player, incoming) : incoming.getCount();
        boolean equipmentLimited = false;
        if (acquisition) {
            int equipmentAllowed = EquipmentInventoryRules.allowed(player, incoming);
            equipmentLimited = equipmentAllowed < incoming.getCount() && equipmentAllowed <= allowed;
            allowed = Math.min(allowed, equipmentAllowed);
        }
        if (weapon(incoming)) {
            int begin = role.equals("handgun") ? 2 : 0, end = role.equals("handgun") ? 3 : 2;
            for (int i = begin; i < end && allowed > 0 && !incoming.isEmpty(); i++) {
                if (!inv.getItem(i).isEmpty()) continue;
                inv.setItem(i, incoming.split(1)); allowed--;
                AutoAttachmentService.onGunAcquired(player, i);
            }
        } else {
            int dedicated = role.equals("free") ? -1 : rules.slot(role);
            if (dedicated >= 0) allowed = put(inv, dedicated, incoming, allowed);
            // The visible main inventory starts at slot 9; it always precedes free hotbar slots.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 9; i < 36 && allowed > 0 && !incoming.isEmpty(); i++) {
                    if (inv.getItem(i).isEmpty() != (pass == 1)) continue;
                    allowed = put(inv, i, incoming, allowed);
                }
            }
            for (int i = 3; i < 9 && allowed > 0 && !incoming.isEmpty(); i++)
                if (rules.hotbar().get(i).equals("free")) allowed = put(inv, i, incoming, allowed);
        }
        inv.setChanged();
        if (!incoming.isEmpty() && allowed <= 0) {
            if (equipmentLimited) EquipmentInventoryRules.warn(player);
            else BackpackService.warn(player);
        }
        return incoming.isEmpty();
    }
    private static int put(Inventory inv, int slot, ItemStack incoming, int allowed) {
        if (allowed <= 0 || incoming.isEmpty()) return allowed;
        ItemStack target = inv.getItem(slot);
        int limit = Math.min(incoming.getMaxStackSize(), inv.getMaxStackSize(incoming));
        if (incoming.getItem() instanceof IAmmo)
            limit = Math.min(limit, InventoryConfig.ammoStackLimit(inv.player, incoming));
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(target, incoming)) return allowed;
        int accepted = Math.min(Math.min(allowed, incoming.getCount()), Math.max(0, limit - target.getCount()));
        if (accepted == 0) return allowed;
        if (target.isEmpty()) inv.setItem(slot, incoming.split(accepted));
        else { target.grow(accepted); incoming.shrink(accepted); }
        return allowed - accepted;
    }
    public static boolean permitsClick(AbstractContainerMenu menu, int index, int button, ClickType type, Player player) {
        if (!InventoryConfig.active(player)) return true;
        if (type == ClickType.CLONE) return false;
        if (type == ClickType.SWAP && (button == 40 || locked(player, button))) return false;
        if (index < 0 || index >= menu.slots.size()) return true;
        Slot slot = menu.slots.get(index);
        if (slot.container == player.getInventory() && locked(player, slot.getContainerSlot()))
            return type == ClickType.THROW;
        if (slot.container != player.getInventory() && EquipmentInventoryRules.category(slot.getItem()) != null
                && InventoryConfig.current(player).autoEquipmentReplacement() && slot.mayPickup(player)
                && (type == ClickType.PICKUP && menu.getCarried().isEmpty()
                    || type == ClickType.SWAP && button >= 0 && button < 9
                        && EquipmentInventoryRules.mayReplace(player, slot.getItem(), true))) {
            if (!player.level().isClientSide) {
                menu.quickMoveStack(player, index); // Keep the real source's recipe/trading callbacks.
                menu.broadcastChanges();
            }
            return false;
        }
        if (!EquipmentInventoryRules.permitsClick(menu, index, button, type, player)) return false;
        // Shift-click must retain the source menu's quickMoveStack transaction and output callbacks.
        if (slot.container != player.getInventory() && weapon(slot.getItem())
                && (type == ClickType.PICKUP || type == ClickType.SWAP && button >= 0 && button < 9)) {
            if (!player.level().isClientSide && (type == ClickType.SWAP || menu.getCarried().isEmpty()) && slot.mayPickup(player)) {
                String role = role(slot.getItem());
                int begin = role.equals("handgun") ? 2 : 0, end = role.equals("handgun") ? 3 : 2;
                boolean space = false;
                for (int i = begin; i < end; i++) space |= player.getInventory().getItem(i).isEmpty();
                boolean owned = com.example.examplemod.backpack.BackpackOwnership.isTemporaryInput(menu, slot);
                if (space && (owned || BackpackService.allowed(player, slot.getItem()) > 0)) {
                    // safeTake invokes onTake, consuming crafting/anvil inputs exactly once.
                    ItemStack taken = slot.safeTake(1, 1, player);
                    insert(player, taken, !owned);
                    if (!taken.isEmpty()) player.drop(taken, false);
                }
                menu.broadcastChanges();
            }
            return false;
        }
        return true;
    }
    private static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        boolean active = InventoryConfig.active(player);
        Sent state = new Sent(active, InventoryConfig.current(player), EquipmentInventoryRules.limit(player));
        if (!state.equals(SENT.get(player.getUUID()))) {
            SENT.put(player.getUUID(), state);
            PacketDistributor.sendToPlayer(player, new InventoryPayloads.State(++revision, active, state.settings, state.equipmentLimit));
        }
        if (!active) { TEMPORARY.remove(player.getUUID()); return; }
        restoreTemporary(player);
        if (!TEMPORARY.containsKey(player.getUUID())) enforceLayout(player);
    }
    private static void restoreTemporary(ServerPlayer player) {
        var operation = TEMPORARY.get(player.getUUID());
        if (operation == null || player.isUsingItem()
                || com.example.examplemod.vitals.PubgMedicineService.pendingContinuation(player)) return;
        var inv = player.getInventory();
        // Restore only the same displaced object; inventory changes cannot manufacture a duplicate gun.
        if (inv.getItem(operation.source) == operation.displaced) {
            ItemStack medicine = inv.getItem(operation.target);
            inv.setItem(operation.target, inv.getItem(operation.source));
            inv.setItem(operation.source, medicine);
            inv.selected = operation.previousSelected;
            player.connection.send(new ClientboundSetCarriedItemPacket(inv.selected));
        }
        TEMPORARY.remove(player.getUUID());
        player.containerMenu.broadcastChanges();
    }
    private static void enforceLayout(ServerPlayer player) {
        Inventory inv = player.getInventory();
        var rules = InventoryConfig.current(player);
        for (int i = 0; i < 36; i++) prepareStack(player, inv.getItem(i));
        for (int i = 0; i < 41; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || i >= 36 && i != 40) continue;
            String role = role(stack);
            boolean wrongGun = weapon(stack) && !(role.equals("handgun") ? i == 2 : i == 0 || i == 1);
            boolean wrongSlot = i < 9 && locked(player, i) && !rules.hotbar().get(i).equals(role);
            if (!wrongGun && !wrongSlot && i != 40) continue;
            inv.setItem(i, ItemStack.EMPTY);
            insert(player, stack, false);
            if (!stack.isEmpty()) player.drop(stack, false); // Preserve any admin/config overflow as a normal player drop.
        }
        for (int i = 3; i < 9; i++) {
            if (!inv.getItem(i).isEmpty() || rules.hotbar().get(i).equals("free")) continue;
            for (int j = 9; j < 36; j++) if (role(inv.getItem(j)).equals(rules.hotbar().get(i))) {
                inv.setItem(i, inv.getItem(j)); inv.setItem(j, ItemStack.EMPTY); break;
            }
        }
    }
    public static void selectAndUse(ServerPlayer player, String action) {
        if (com.example.examplemod.combat.CombatService.actionBlocked(player)) return;
        if (!InventoryConfig.active(player) || player.isUsingItem() || player.containerMenu != player.inventoryMenu) return;
        Inventory inv = player.getInventory();
        boolean throwable = action.equals("throwable") || java.util.Set.of("frag_grenade", "stun_grenade",
                "smoke_grenade", "molotov", "sticky_bomb", "c4", "blue_zone_grenade").contains(action);
        int source = -1;
        int start = action.equals("throwable") ? (inv.selected + 1) % 36 : 0;
        for (int offset = 0; offset < 36; offset++) {
            int i = (start + offset) % 36;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (action.equals(id) || action.equals("boost") && role(stack).equals("boost")
                    || action.equals("throwable") && role(stack).equals("throwable")) { source = i; break; }
        }
        if (source < 0) { player.displayClientMessage(Component.translatable("message.tacz_pubg.inventory.item_missing"), true); return; }
        ItemStack selected = inv.getItem(source);
        String role = role(selected);
        int target = InventoryConfig.current(player).slot(role);
        boolean temporary = target < 0;
        if (temporary) target = InventoryConfig.current(player).slot("free");
        if (target < 0 && throwable) {
            player.displayClientMessage(Component.translatable("message.tacz_pubg.inventory.no_throwable_slot"), true);
            return;
        }
        if (target < 0) target = inv.selected;
        if (source != target) {
            ItemStack displaced = inv.getItem(target);
            inv.setItem(target, selected); inv.setItem(source, displaced);
            if (!throwable && temporary) TEMPORARY.put(player.getUUID(), new TemporaryUse(source, target, inv.selected, displaced));
        }
        inv.selected = target;
        player.connection.send(new ClientboundSetCarriedItemPacket(target));
        player.containerMenu.broadcastChanges();
        if (!throwable && selected.getItem() instanceof PubgMedicineItem medicine) {
            medicine.startTreatment(player.level(), player, InteractionHand.MAIN_HAND);
            if (player.isUsingItem()) ThrowablePayloads.quickUseStarted(player, medicine.getUseDuration(selected, player));
        }
    }
}
