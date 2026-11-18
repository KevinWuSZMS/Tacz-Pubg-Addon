package com.example.examplemod.inventory;

import com.example.examplemod.backpack.BackpackItem;
import com.example.examplemod.backpack.BackpackRules;
import com.example.examplemod.backpack.BackpackService;
import com.example.examplemod.backpack.BackpackWeights;
import com.example.examplemod.combat.CombatService;
import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootWorldPhysics;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.example.examplemod.lootspawn.DroppedLootPolicy;
import com.example.examplemod.inventory.PubgInventoryPayloads.*;
import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.util.AttachmentDataUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Shared authority for the PUBG view; the vanilla inventory remains the only item storage. */
public final class PubgInventoryService {
    private static final Map<UUID, ArrayDeque<Snapshot>> SNAPSHOTS = new HashMap<>();
    private static final Map<UUID, List<Recent>> RECENT = new HashMap<>();
    private static final Map<UUID, Long> LAST_REFRESH = new HashMap<>();
    private static final Map<UUID, UUID> SESSIONS = new HashMap<>();
    private static final Map<UUID, String> FAILURE = new HashMap<>();
    private static long revision, pickupOrder;
    private PubgInventoryService() {}
    public static void register(IEventBus modBus, IEventBus gameBus) {
        modBus.addListener(PubgInventoryPayloads::register);
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            UUID id = e.getEntity().getUUID(); SNAPSHOTS.remove(id); RECENT.remove(id); LAST_REFRESH.remove(id); SESSIONS.remove(id); FAILURE.remove(id);
        });
        gameBus.addListener((ServerStoppedEvent e) -> { SNAPSHOTS.clear(); RECENT.clear(); LAST_REFRESH.clear(); SESSIONS.clear(); FAILURE.clear(); revision = pickupOrder = 0; });
    }
    private static void session(ServerPlayer player) {
        var game = com.example.examplemod.zones.match.MatchService.participantGame(player.server, player.getUUID());
        if (game == null) return;
        UUID current = game.session().sessionId();
        if (!current.equals(SESSIONS.put(player.getUUID(), current))) {
            RECENT.remove(player.getUUID()); SNAPSHOTS.remove(player.getUUID());
        }
    }

    /** Only successful external acquisitions update this history; rearranging a slot does not. */
    public static void acquired(Player player, ItemStack item) {
        if (!(player instanceof ServerPlayer serverPlayer) || !InventoryConfig.active(player) || item.isEmpty()) return;
        session(serverPlayer);
        ItemStack key = item.copyWithCount(1);
        MatchInventoryService.prepareStack(player, key);
        List<Recent> list = RECENT.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>());
        list.removeIf(entry -> ItemStack.isSameItemSameComponents(entry.item(), key));
        list.add(new Recent(key, ++pickupOrder));
        while (list.size() > 256) list.removeFirst();
    }

    public static boolean nearby(ServerPlayer player, GroundLootEntity loot) {
        if (!player.isAlive() || player.isSpectator() || loot.isRemoved() || !loot.isManagedLoot()
                || loot.level() != player.level() || loot.getItem().isEmpty() || loot.getQuantity() <= 0
                || !loot.mayBeListedFor(player) || !PubgInventoryRules.withinNearby(player.getX() - loot.getX(),
                    player.getY() - loot.getY(), player.getZ() - loot.getZ())) return false;
        var end = loot.position().add(0, .1, 0);
        var hit = player.level().clip(new ClipContext(player.getEyePosition(), end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(end) < .0025;
    }

    private static void sendSnapshot(ServerPlayer player) {
        session(player);
        var items = new ArrayList<ItemStack>(41);
        for (int i = 0; i < 41; i++) items.add(player.getInventory().getItem(i).copy());
        var around = player.level().getEntitiesOfClass(GroundLootEntity.class,
                new AABB(player.position(), player.position()).inflate(1.1), loot -> nearby(player, loot));
        around.sort(Comparator.comparingDouble((GroundLootEntity e) -> player.position().distanceToSqr(e.position())).thenComparingInt(GroundLootEntity::getId));
        var rows = new ArrayList<Nearby>();
        for (GroundLootEntity loot : around) {
            if (rows.size() == 64) break;
            rows.add(new Nearby(loot.getId(), loot.getUUID(), loot.getItem().copyWithCount(1), loot.getQuantity()));
        }
        Snapshot state = new Snapshot(++revision, List.copyOf(rows), List.copyOf(items), BackpackService.equipped(player).copy(),
                List.copyOf(RECENT.getOrDefault(player.getUUID(), List.of())));
        var retained = SNAPSHOTS.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        retained.addLast(state); while (retained.size() > 8) retained.removeFirst();
        PacketDistributor.sendToPlayer(player, state);
    }

    public static void handle(ServerPlayer player, Command command) {
        if (!InventoryConfig.active(player) || !player.isAlive() || player.isSpectator()
                || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return;
        if (command.action() == Action.REFRESH) {
            long now = player.level().getGameTime(), last = LAST_REFRESH.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
            if (now - last >= 2) { LAST_REFRESH.put(player.getUUID(), now); sendSnapshot(player); }
            return;
        }
        if (CombatService.actionBlocked(player) || AutoAttachmentService.busy(player)) { fail(player, message("busy")); return; }
        Snapshot state = SNAPSHOTS.getOrDefault(player.getUUID(), new ArrayDeque<>()).stream()
                .filter(s -> s.revision() == command.revision()).findFirst().orElse(null);
        if (state == null) { fail(player, message("updated")); return; }
        GroundLootEntity ground = null;
        ItemStack source;
        if (command.ground()) {
            Nearby expected = state.nearby().stream().filter(n -> n.entityId() == command.source()).findFirst().orElse(null);
            if (expected == null || !(player.serverLevel().getEntity(command.source()) instanceof GroundLootEntity loot)
                    || !expected.uuid().equals(loot.getUUID()) || !nearby(player, loot)
                    || !ItemStack.isSameItemSameComponents(expected.item(), loot.getItem()) || expected.quantity() != loot.getQuantity()) {
                fail(player, message("ground_changed")); return;
            }
            if (!loot.canBePickedUpBy(player)) { fail(player, message("busy")); return; }
            ground = loot; source = loot.getItem();
        } else {
            if (command.source() < 0 || command.source() > 41 || !unchanged(player, state, command.source())) {
                fail(player, message("source_changed")); return;
            }
            source = live(player, command.source());
        }
        if (source.isEmpty()) { fail(player, message("source_empty")); return; }
        if (command.target() >= 0 && (command.target() > 41 || !unchanged(player, state, command.target()))) {
            fail(player, message("target_changed")); return;
        }
        FAILURE.remove(player.getUUID());
        boolean success = switch (command.action()) {
            case PICKUP -> ground != null && pickup(player, ground);
            case RIGHT_CLICK -> !command.ground() && rightClick(player, command.source(), source);
            case MOVE -> move(player, command.source(), command.target(), ground);
            case ATTACH -> attach(player, command.source(), command.target(), ground, command.attachmentType(), false);
            case UNLOAD -> ground == null && transferAttachment(player, command.source(), command.target(), command.attachmentType());
            case DROP -> ground == null && drop(player, command.source());
            case REMOVE_BACKPACK -> ground == null && removeBackpack(player);
            case REFRESH -> false;
        };
        if (!success) fail(player, FAILURE.getOrDefault(player.getUUID(), message("cannot_place")));
        else {
            changed(player);
            if (player.getMainHandItem().getItem() instanceof IGun) {
                AttachmentPropertyManager.postChangeEvent(player, player.getMainHandItem());
                PacketDistributor.sendToPlayer(player, new Feedback("", ItemStack.EMPTY, false, player.getInventory().selected));
            }
            sendSnapshot(player);
        }
        FAILURE.remove(player.getUUID());
    }

    private static boolean unchanged(ServerPlayer player, Snapshot state, int slot) {
        return ItemStack.matches(slot == 41 ? state.backpack() : state.inventory().get(slot), live(player, slot));
    }
    private static ItemStack live(Player player, int slot) { return slot == 41 ? BackpackService.equipped(player) : player.getInventory().getItem(slot); }
    private static void fail(ServerPlayer player, String message) {
        PacketDistributor.sendToPlayer(player, new Feedback(message, ItemStack.EMPTY, false, -1)); sendSnapshot(player);
    }
    private static String message(String key) {
        return com.example.examplemod.localization.Texts.tr("message.tacz_pubg.inventory.pubg." + key);
    }
    private static void changed(ServerPlayer player) {
        player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges();
    }
    private static void sound(ServerPlayer player, ItemStack item, boolean uninstall, int gunSlot) {
        if (player.getInventory().selected == gunSlot) AttachmentPropertyManager.postChangeEvent(player, player.getMainHandItem());
        changed(player);
        PacketDistributor.sendToPlayer(player, new Feedback("", item.copyWithCount(1), uninstall, gunSlot));
    }

    private static boolean pickup(ServerPlayer player, GroundLootEntity loot) {
        return pickup(player, loot, InventoryConfig.current(player).pubgDirectEquip());
    }

    private static boolean pickup(ServerPlayer player, GroundLootEntity loot, boolean directEquip) {
        ItemStack source = loot.getItem();
        if (directEquip) {
            if (source.getItem() instanceof IAttachment attachment) {
                int target = attachmentTarget(player, source, attachment.getType(source));
                // Once selected, a failed replacement must not silently modify a different gun.
                if (target >= 0) return attach(player, -1, target, loot, attachment.getType(source).name(), false);
            }
            int equipment = equipmentSlot(player, source);
            if (equipment >= 0) return move(player, -1, equipment, loot);
            if (MatchInventoryService.weapon(source)) {
                int[] priority = PubgInventoryTargets.priority(player.getInventory().selected);
                for (int target : priority) if (accepts(player, target, source) && live(player, target).isEmpty())
                    return move(player, -1, target, loot);
                int selected = player.getInventory().selected;
                return selected < 3 && accepts(player, selected, source) && move(player, -1, selected, loot);
            }
        }
        return storeGround(player, loot);
    }

    /** Explicitly collecting into the list never invokes passive auto-equipment hooks. */
    private static boolean storeGround(ServerPlayer player, GroundLootEntity ground) {
        if (MatchInventoryService.weapon(ground.getItem())) return false;
        Plan plan = new Plan(player);
        ItemStack source = ground.getItem().copy();
        MatchInventoryService.prepareStack(player, source);
        int low = 0, high = Math.min(ground.getQuantity(), 33 * Math.min(source.getMaxStackSize(),
                player.getInventory().getMaxStackSize(source)));
        ItemStack[] before = plan.copyItems();
        // Find the largest quantity that satisfies both physical slots and final carrying limits.
        while (low < high) {
            int amount = low + (high - low + 1) / 2;
            plan.restoreItems(before);
            if (plan.store(source.copyWithCount(amount)) && plan.valid()) low = amount;
            else high = amount - 1;
        }
        plan.restoreItems(before);
        if (low == 0 || !plan.store(source.copyWithCount(low))) return false;
        if (!plan.finish(ground, ground.getItem().copy(), low)) return false;
        acquired(player, source); return true;
    }

    private static int equipmentSlot(Player player, ItemStack source) {
        if (BackpackService.isBackpack(source)) return 41;
        EquipmentSlot slot = player.getEquipmentSlotForItem(source);
        return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? 36 + slot.getIndex() : -1;
    }

    private static int attachmentTarget(ServerPlayer player, ItemStack incoming, AttachmentType type) {
        for (int slot : PubgInventoryTargets.priority(player.getInventory().selected)) {
            ItemStack gun = live(player, slot);
            if (!compatible(gun, incoming, type)) continue;
            ItemStack old = ((IGun) gun.getItem()).getAttachment(player.registryAccess(), gun, type);
            if (!ItemStack.isSameItemSameComponents(old, incoming)) return slot;
        }
        return -1;
    }

    private static boolean rightClick(ServerPlayer player, int from, ItemStack source) {
        if (from >= 36 && from <= 39) return move(player, from, -1, null);
        if (source.getItem() instanceof IAttachment attachment) {
            int target = attachmentTarget(player, source, attachment.getType(source));
            return target >= 0 && attach(player, from, target, null, attachment.getType(source).name(), false);
        }
        if (source.getItem() instanceof BackpackItem || source.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem)
            return from == 41 ? removeBackpack(player) : move(player, from, 41, null);
        EquipmentSlot armor = player.getEquipmentSlotForItem(source);
        if (armor.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return move(player, from, 36 + armor.getIndex(), null);
        if (source.getItem() instanceof com.example.examplemod.vitals.PubgMedicineItem) {
            MatchInventoryService.selectAndUse(player, BuiltInRegistries.ITEM.getKey(source.getItem()).getPath());
            return player.isUsingItem();
        }
        // Gun cards reserve left-click for refitting and dragging for explicit slot changes.
        if (MatchInventoryService.weapon(source)) return false;
        String role = MatchInventoryService.role(source); int target = InventoryConfig.current(player).slot(role);
        return target >= 0 && from != target && move(player, from, target, null);
    }

    private static boolean removeBackpack(ServerPlayer player) {
        return move(player, 41, -1, null);
    }

    private static boolean move(ServerPlayer player, int from, int to, GroundLootEntity ground) {
        if (to < -1 || to == 40 || to > 41 || ground == null && from == to) return false;
        if (ground == null && (from < 0 || from > 41 || from == 40)) return false;
        if (to == -1 && ground != null) return storeGround(player, ground);
        ItemStack source = (ground == null ? live(player, from) : ground.getItem()).copy();
        if (source.isEmpty()) return false;
        Plan plan = new Plan(player);
        if (ground == null && from >= 36 && !canRemove(player, source)) return false;
        if (to >= 36 && !canRemove(player, plan.get(to))) return false;
        int amount = 0;
        if (to == -1) {
            if (from < 36) return false;
            plan.set(from, ItemStack.EMPTY);
            plan.returnOrDrop(source);
        } else {
            if (!accepts(player, to, source)) return false;
            amount = to >= 36 || MatchInventoryService.weapon(source) ? 1 : source.getCount();
            ItemStack old = plan.get(to).copy();
            if (ground == null && to < 36 && from < 36) {
                if (!old.isEmpty() && !accepts(player, from, old)) return false;
                if (amount == source.getCount()) { plan.set(to, source); plan.set(from, old); }
                else { plan.items[from].shrink(amount); plan.set(to, source.copyWithCount(amount)); plan.returnOrDrop(old); }
            } else {
                if (ground == null) {
                    if (from >= 36) return false;
                    plan.items[from].shrink(amount);
                }
                ItemStack replacement = source.copyWithCount(amount);
                if (ground != null && MatchInventoryService.weapon(replacement))
                    AutoAttachmentService.transferCompatibleAttachments(player, old, replacement);
                plan.set(to, replacement);
                plan.returnOrDrop(old);
            }
        }
        if (!plan.finish(ground, source, amount)) return false;
        if (ground != null) acquired(player, source);
        return true;
    }

    private static boolean accepts(Player player, int slot, ItemStack item) {
        if (item.isEmpty()) return true;
        if (slot == 41) return item.getCount() == 1 && BackpackService.isBackpack(item);
        if (slot < 0 || slot == 40 || slot > 39) return false;
        if (slot >= 36) return item.getCount() == 1 && !(item.getItem() instanceof BackpackItem)
                && !(item.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem)
                && player.getEquipmentSlotForItem(item).getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                && player.getEquipmentSlotForItem(item).getIndex() == slot - 36;
        String role = MatchInventoryService.role(item);
        if (MatchInventoryService.weapon(item)) return role.equals("handgun") ? slot == 2 : slot == 0 || slot == 1;
        if (slot < 3) return false;
        return slot >= 9 || InventoryConfig.current(player).hotbar().get(slot).equals("free")
                || InventoryConfig.current(player).hotbar().get(slot).equals(role);
    }
    private static boolean canRemove(Player player, ItemStack stack) {
        return player.isCreative() || !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private static boolean compatible(ItemStack gun, ItemStack attachment, AttachmentType type) {
        return gun.getCount() == 1 && gun.getItem() instanceof IGun api && type != AttachmentType.NONE
                && !api.hasAttachmentLock(gun) && api.allowAttachmentType(gun, type)
                && DefaultAssets.isEmptyAttachmentId(api.getBuiltInAttachmentId(gun, type)) && api.allowAttachment(gun, attachment);
    }
    private static boolean attach(ServerPlayer player, int from, int target, GroundLootEntity ground, String typeName, boolean unload) {
        if (target < 0 || target > 2) return false;
        AttachmentType type;
        try { type = AttachmentType.valueOf(typeName.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return false; }
        Plan plan = new Plan(player); ItemStack gun = plan.items[target];
        if (gun.getCount() != 1 || !(gun.getItem() instanceof IGun api) || api.hasAttachmentLock(gun)
                || type == AttachmentType.NONE || !api.allowAttachmentType(gun, type)
                || !DefaultAssets.isEmptyAttachmentId(api.getBuiltInAttachmentId(gun, type))) return false;
        ItemStack old = api.getAttachment(player.registryAccess(), gun, type).copy();
        if (old.isEmpty() && !DefaultAssets.isEmptyAttachmentId(api.getAttachmentId(gun, type))) return false;
        if (!old.isEmpty() && !physicalAttachment(old, type)) return false;
        ItemStack incoming = unload ? ItemStack.EMPTY : (ground == null ? live(player, from) : ground.getItem()).copy();
        if (unload) {
            if (old.getCount() != 1 || !(old.getItem() instanceof IAttachment part) || part.getType(old) != type) return false;
            api.unloadAttachment(player.registryAccess(), gun, type);
            if (!api.getAttachment(player.registryAccess(), gun, type).isEmpty()
                    || !DefaultAssets.isEmptyAttachmentId(api.getAttachmentId(gun, type))) return false;
        } else {
            if (!(incoming.getItem() instanceof IAttachment attachment) || attachment.getType(incoming) != type
                    || !compatible(gun, incoming, type) || ItemStack.isSameItemSameComponents(old, incoming)) return false;
            api.installAttachment(player.registryAccess(), gun, incoming.copyWithCount(1));
            if (!ItemStack.matches(incoming.copyWithCount(1), api.getAttachment(player.registryAccess(), gun, type))) return false;
            if (ground == null) plan.items[from].shrink(1);
        }
        if (!preservesAmmunition(player, live(player, target), gun, api)) return false;
        plan.returnOrDrop(old);
        if (!plan.finish(ground, incoming, 1)) return false;
        if (ground != null) acquired(player, incoming);
        sound(player, unload ? old : incoming, unload, target);
        return true;
    }

    /** One copied plan owns source unload, destination replacement, and any displaced attachment. */
    private static boolean transferAttachment(ServerPlayer player, int source, int target, String typeName) {
        if (source < 0 || source > 2 || target < -2 || target > 2 || target == source) return false;
        if (target == -1) return attach(player, -1, source, null, typeName, true);
        AttachmentType type;
        try { type = AttachmentType.valueOf(typeName.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return false; }
        Plan plan = new Plan(player);
        ItemStack gun = plan.items[source];
        if (gun.getCount() != 1 || !(gun.getItem() instanceof IGun api) || api.hasAttachmentLock(gun)
                || type == AttachmentType.NONE || !api.allowAttachmentType(gun, type)
                || !DefaultAssets.isEmptyAttachmentId(api.getBuiltInAttachmentId(gun, type))) return false;
        ItemStack attachment = api.getAttachment(player.registryAccess(), gun, type).copy();
        // Built-in fallback assets are not physical items and may never be extracted.
        if (attachment.getCount() != 1 || !(attachment.getItem() instanceof IAttachment part) || part.getType(attachment) != type) return false;
        api.unloadAttachment(player.registryAccess(), gun, type);
        if (!api.getAttachment(player.registryAccess(), gun, type).isEmpty()
                || !DefaultAssets.isEmptyAttachmentId(api.getAttachmentId(gun, type))) return false;
        if (target >= 0) {
            ItemStack destination = plan.items[target];
            if (!compatible(destination, attachment, type)) return false;
            IGun destinationApi = (IGun) destination.getItem();
            ItemStack displaced = destinationApi.getAttachment(player.registryAccess(), destination, type).copy();
            if (displaced.isEmpty() && !DefaultAssets.isEmptyAttachmentId(destinationApi.getAttachmentId(destination, type))
                    || !displaced.isEmpty() && !physicalAttachment(displaced, type)
                    || ItemStack.isSameItemSameComponents(displaced, attachment)) return false;
            destinationApi.installAttachment(player.registryAccess(), destination, attachment.copy());
            if (!ItemStack.matches(attachment, destinationApi.getAttachment(player.registryAccess(), destination, type))
                    || !preservesAmmunition(player, live(player, target), destination, destinationApi)) return false;
            if (!displaced.isEmpty() && compatible(gun, displaced, type)) {
                api.installAttachment(player.registryAccess(), gun, displaced.copy());
                if (!ItemStack.matches(displaced, api.getAttachment(player.registryAccess(), gun, type))) return false;
            } else plan.returnOrDrop(displaced);
        }
        // A magazine exchange is checked after both final magazines are present, never at the
        // temporary unloaded state, which may be smaller than either physical magazine.
        if (!preservesAmmunition(player, live(player, source), gun, api)) return false;
        if (target == -2) plan.drops.add(attachment.copy());
        if (!plan.finish(null, ItemStack.EMPTY, 0)) return false;
        sound(player, attachment, true, source);
        if (target >= 0) sound(player, attachment, false, target);
        return true;
    }

    private static boolean physicalAttachment(ItemStack item, AttachmentType type) {
        return item.getCount() == 1 && item.getItem() instanceof IAttachment attachment && attachment.getType(item) == type;
    }

    private static boolean preservesAmmunition(ServerPlayer player, ItemStack originalGun, ItemStack gun, IGun api) {
        int capacity = TimelessAPI.getCommonGunIndex(api.getGunId(gun))
                .map(index -> AttachmentDataUtils.getAmmoCountWithAttachment(gun, index.getGunData())).orElse(-1);
        if (!PubgInventoryRules.preservesAmmunition(api.getCurrentAmmoCount(originalGun), api.hasBulletInBarrel(originalGun),
                api.getCurrentAmmoCount(gun), api.hasBulletInBarrel(gun), capacity)) {
            FAILURE.put(player.getUUID(), message("magazine_full")); return false;
        }
        return true;
    }

    private static boolean drop(ServerPlayer player, int from) {
        if (from < 0 || from > 41 || from == 40) return false;
        ItemStack original = live(player, from);
        if (original.isEmpty() || from >= 36 && !canRemove(player, original)) return false;
        Plan plan = new Plan(player);
        if (from == 41) plan.pack = ItemStack.EMPTY;
        else plan.items[from] = ItemStack.EMPTY;
        plan.drops.add(original.copy());
        return plan.finish(null, ItemStack.EMPTY, 0);
    }

    private static GroundLootEntity prepareDrop(ServerPlayer player, ItemStack original) {
        // Keep vanilla ownership/pickup delay while checking the actual entity spawn result.
        var previousCapture = player.captureDrops(new ArrayList<>());
        net.minecraft.world.entity.item.ItemEntity dropped;
        try { dropped = player.drop(original.copy(), false, false); }
        finally { player.captureDrops(previousCapture); }
        if (dropped == null) return null;
        var toss = new net.neoforged.neoforge.event.entity.item.ItemTossEvent(dropped, player);
        if (net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(toss).isCanceled()) return null;
        dropped = toss.getEntity();
        // A callback may veto or alter metadata, but may not substitute/resize the physical item.
        if (!ItemStack.matches(original, dropped.getItem())) return null;
        var data = new net.minecraft.nbt.CompoundTag();
        dropped.addAdditionalSaveData(data);
        int delay = data.getShort("PickupDelay");
        if (!DroppedLootPolicy.legalDelay(delay) || !DroppedLootPolicy.legalQuantity(dropped.getItem().getCount())) return null;
        GroundLootEntity carrier = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
        if (carrier == null) return null;
        // The authority creates the final carrier immediately; display never waits for pickup eligibility.
        carrier.initializeDrop(dropped.getItem(), player.blockPosition().below(), delay, dropped.getTarget());
        carrier.setPos(player.getX(), player.getY() + .2, player.getZ());
        carrier.setYRot(DroppedLootPolicy.yaw(dropped)); carrier.yRotO = carrier.getYRot();
        carrier.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        carrier.setCustomName(dropped.getCustomName()); carrier.setCustomNameVisible(dropped.isCustomNameVisible());
        GroundLootWorldPhysics.prepareSpawn(carrier);
        com.example.examplemod.lootspawn.RoundLootService.markCurrentRound(player.serverLevel(), carrier.getX(), carrier.getZ(), carrier.getItem());
        return carrier;
    }

    private static final class Plan {
        final ServerPlayer player;
        final ItemStack[] items = new ItemStack[41];
        final ItemStack[] original = new ItemStack[41];
        final ItemStack originalPack;
        final ItemStack originalCursor;
        final List<ItemStack> originalTemporary;
        final int selected, beforeUsed, beforeCapacity, equipmentLimit;
        final int[] beforeEquipment;
        final InventorySettings settings;
        final boolean active;
        final List<ItemStack> drops = new ArrayList<>();
        ItemStack pack;
        Plan(ServerPlayer player) {
            this.player = player; originalPack = BackpackService.equipped(player).copy(); pack = originalPack.copy();
            originalCursor = player.containerMenu.getCarried().copy(); selected = player.getInventory().selected;
            originalTemporary = com.example.examplemod.backpack.BackpackOwnership.temporaryInputs(player).stream()
                    .map(slot -> slot.getItem().copy()).toList();
            beforeUsed = BackpackService.used(player); beforeCapacity = BackpackService.capacity(player);
            equipmentLimit = EquipmentInventoryRules.limit(player); beforeEquipment = EquipmentInventoryRules.carried(player);
            settings = InventoryConfig.current(player);
            active = InventoryConfig.active(player);
            for (int i = 0; i < 41; i++) { original[i] = live(player, i).copy(); items[i] = original[i].copy(); }
        }
        ItemStack get(int slot) { return slot == 41 ? pack : items[slot]; }
        void set(int slot, ItemStack value) { if (slot == 41) pack = value.copy(); else items[slot] = value.copy(); }
        ItemStack[] copyItems() {
            ItemStack[] copy = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) copy[i] = items[i].copy();
            return copy;
        }
        void restoreItems(ItemStack[] saved) { for (int i = 0; i < items.length; i++) items[i] = saved[i].copy(); }
        void returnOrDrop(ItemStack returning) {
            if (returning.isEmpty()) return;
            ItemStack[] saved = copyItems();
            if (store(returning.copy()) && valid()) return;
            restoreItems(saved);
            drops.add(returning.copy());
        }
        boolean store(ItemStack returning) {
            if (returning.isEmpty()) return true;
            MatchInventoryService.prepareStack(player, returning);
            if (MatchInventoryService.weapon(returning)) return false;
            String role = MatchInventoryService.role(returning);
            int dedicated = role.equals("free") ? -1 : settings.slot(role);
            if (dedicated >= 3) put(dedicated, returning);
            if (returning.isEmpty()) return true;
            for (int pass = 0; pass < 2; pass++) for (int index = 0; index < 33; index++) {
                int slot = index < 27 ? index + 9 : index - 24;
                if (!accepts(player, slot, returning)) continue;
                ItemStack existing = items[slot];
                if (existing.isEmpty() != (pass == 1) || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, returning)) continue;
                put(slot, returning);
                if (returning.isEmpty()) return true;
            }
            return false;
        }
        void put(int slot, ItemStack returning) {
            ItemStack existing = items[slot];
            if (!accepts(player, slot, returning) || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, returning)) return;
            int limit = Math.min(returning.getMaxStackSize(), player.getInventory().getMaxStackSize(returning));
            if (returning.getItem() instanceof com.tacz.guns.api.item.IAmmo) limit = Math.min(limit, InventoryConfig.ammoStackLimit(player, returning));
            int amount = Math.min(returning.getCount(), Math.max(0, limit - existing.getCount()));
            if (amount <= 0) return;
            if (existing.isEmpty()) items[slot] = returning.split(amount);
            else { existing.grow(amount); returning.shrink(amount); }
        }
        boolean valid() {
            long after = beforeUsed;
            for (int i = 0; i < 36; i++) after += (long) weight(i, items[i]) - weight(i, original[i]);
            int tier = pack.getItem() instanceof BackpackItem backpack ? backpack.tier() : 0;
            int capacity = BackpackRules.capacity(tier, items[38].getItem() instanceof ArmorItem);
            if (!PubgInventoryRules.capacityChange(beforeUsed, after, beforeCapacity, capacity)) return false;
            if (equipmentLimit > 0) {
                int[] next = beforeEquipment.clone();
                for (int i = 0; i < 41; i++) {
                    int[] old = EquipmentInventoryRules.counts(original[i]), now = EquipmentInventoryRules.counts(items[i]);
                    for (int c = 0; c < 3; c++) next[c] += now[c] - old[c];
                }
                int[] oldPack = EquipmentInventoryRules.counts(originalPack), nextPack = EquipmentInventoryRules.counts(pack);
                for (int c = 0; c < 3; c++) next[c] += nextPack[c] - oldPack[c];
                for (int c = 0; c < 3; c++) if (next[c] > Math.max(equipmentLimit, beforeEquipment[c])) return false;
            }
            return true;
        }
        boolean stillCurrent() {
            if (!player.isAlive() || player.isSpectator() || player.containerMenu != player.inventoryMenu
                    || selected != player.getInventory().selected || !settings.equals(InventoryConfig.current(player))
                    || active != InventoryConfig.active(player)
                    || !ItemStack.matches(originalCursor, player.containerMenu.getCarried())
                    || !ItemStack.matches(originalPack, BackpackService.equipped(player))) return false;
            for (int i = 0; i < 41; i++) if (!ItemStack.matches(original[i], live(player, i))) return false;
            var temporary = com.example.examplemod.backpack.BackpackOwnership.temporaryInputs(player);
            if (temporary.size() != originalTemporary.size()) return false;
            for (int i = 0; i < temporary.size(); i++) if (!ItemStack.matches(originalTemporary.get(i), temporary.get(i).getItem())) return false;
            return true;
        }
        int weight(int slot, ItemStack stack) {
            boolean melee = slot < 9 && InventoryConfig.current(player).hotbar().get(slot).equals("melee")
                    && MatchInventoryService.role(stack).equals("melee")
                    && !InventoryConfig.current(player).capacityOverrides().containsKey(InventoryConfig.key(stack));
            return melee ? 0 : BackpackWeights.weight(player, stack);
        }
        boolean finish(GroundLootEntity ground, ItemStack expected, int amount) {
            expected = expected.copy();
            if (!valid() || !stillCurrent()) return false;
            int quantity = ground == null ? 0 : ground.getQuantity();
            if (ground != null && !groundCurrent(ground, expected, quantity, amount)) return false;
            List<GroundLootEntity> spawned = new ArrayList<>();
            List<ItemStack> expectedDrops = new ArrayList<>();
            boolean committed = false, wroteInventory = false;
            try {
                for (ItemStack item : drops) {
                    GroundLootEntity carrier = prepareDrop(player, item);
                    if (carrier == null) return false;
                    spawned.add(carrier);
                    expectedDrops.add(carrier.getItem().copyWithCount(carrier.getQuantity()));
                    if (!stillCurrent() || !player.level().addFreshEntity(carrier)) return false;
                }
                if (!stillCurrent() || !valid() || ground != null && !groundCurrent(ground, expected, quantity, amount)) return false;
                for (int i = 0; i < spawned.size(); i++) {
                    GroundLootEntity carrier = spawned.get(i); ItemStack item = expectedDrops.get(i);
                    if (carrier.isRemoved() || carrier.getQuantity() != item.getCount()
                            || !ItemStack.isSameItemSameComponents(carrier.getItem(), item)) return false;
                }
                wroteInventory = true;
                apply();
                if (ground != null && !ground.consumeForInventory(player, expected, amount)) return false;
                committed = true;
                changed(player);
                return true;
            } catch (RuntimeException error) {
                com.example.examplemod.ExampleMod.LOGGER.warn("Inventory transaction failed; uncommitted items retained", error);
                return false;
            } finally {
                if (!committed) {
                    spawned.forEach(GroundLootEntity::discard);
                    if (wroteInventory) {
                        for (int i = 0; i < 41; i++) player.getInventory().setItem(i, original[i].copy());
                        if (!ItemStack.matches(originalPack, BackpackService.equipped(player))) BackpackService.setEquipped(player, originalPack);
                        changed(player);
                    }
                }
            }
        }
        boolean groundCurrent(GroundLootEntity ground, ItemStack expected, int quantity, int amount) {
            return nearby(player, ground) && ground.canBePickedUpBy(player) && amount > 0 && amount <= quantity
                    && quantity == ground.getQuantity() && ItemStack.isSameItemSameComponents(expected, ground.getItem());
        }
        void apply() {
            for (int i = 0; i < 41; i++) if (!ItemStack.matches(items[i], live(player, i))) player.getInventory().setItem(i, items[i]);
            if (!ItemStack.matches(originalPack, pack)) BackpackService.setEquipped(player, pack);
        }
    }
}
