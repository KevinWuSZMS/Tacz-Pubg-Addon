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
        ItemStack before = loot.getItem().copy(); int count = loot.getQuantity();
        ItemStack[] guns = {live(player, 0).copy(), live(player, 1).copy(), live(player, 2).copy()};
        loot.pickup(player);
        boolean taken = loot.isRemoved() || count != loot.getQuantity() || !ItemStack.isSameItemSameComponents(before, loot.getItem());
        if (taken) {
            acquired(player, before);
            if (before.getItem() instanceof IAttachment) for (int i = 0; i < 3; i++)
                if (!ItemStack.matches(guns[i], live(player, i))) { sound(player, before, false, i); break; }
        }
        return taken;
    }

    private static boolean rightClick(ServerPlayer player, int from, ItemStack source) {
        if (from >= 36 && from <= 39) return move(player, from, -1, null);
        if (source.getItem() instanceof IAttachment attachment) {
            List<Integer> priority = new ArrayList<>(); int selected = player.getInventory().selected;
            if (selected < 3) priority.add(selected);
            for (int i = 0; i < 3; i++) if (!priority.contains(i)) priority.add(i);
            String rejectedReason = null;
            for (int slot : priority) if (compatible(live(player, slot), source, attachment.getType(source))) {
                FAILURE.remove(player.getUUID());
                if (attach(player, from, slot, null, attachment.getType(source).name(), false)) {
                    FAILURE.remove(player.getUUID());
                    return true;
                }
                String reason = FAILURE.remove(player.getUUID());
                if (rejectedReason == null) rejectedReason = reason;
            }
            if (rejectedReason != null) FAILURE.put(player.getUUID(), rejectedReason);
            return false;
        }
        if (source.getItem() instanceof BackpackItem || source.getItem() instanceof com.example.examplemod.zones.match.flight.ParachuteItem)
            return from == 41 ? removeBackpack(player) : move(player, from, 41, null);
        EquipmentSlot armor = player.getEquipmentSlotForItem(source);
        if (armor.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return move(player, from, 36 + armor.getIndex(), null);
        if (source.getItem() instanceof com.example.examplemod.vitals.PubgMedicineItem) {
            MatchInventoryService.selectAndUse(player, BuiltInRegistries.ITEM.getKey(source.getItem()).getPath());
            return player.isUsingItem();
        }
        String role = MatchInventoryService.role(source); int target = InventoryConfig.current(player).slot(role);
        return target >= 0 && from != target && move(player, from, target, null);
    }

    private static boolean removeBackpack(ServerPlayer player) {
        ItemStack before = BackpackService.equipped(player).copy();
        BackpackService.removeToInventory(player);
        return !before.isEmpty() && BackpackService.equipped(player).isEmpty();
    }

    private static boolean move(ServerPlayer player, int from, int to, GroundLootEntity ground) {
        if (to < -1 || to == 40 || to > 41 || ground == null && from == to) return false;
        ItemStack source = ground == null ? live(player, from) : ground.getItem();
        if (to == -1 && ground != null) return pickup(player, ground);
        if (to == 41 && ground == null && from < 36) {
            ItemStack before = BackpackService.equipped(player).copy(); BackpackService.equipInventory(player, from);
            return !ItemStack.matches(before, BackpackService.equipped(player));
        }
        if (ground != null && MatchInventoryService.weapon(source) && to >= 0 && to < 3) {
            ItemStack original = source.copy(); boolean result = ground.exchangeWeapon(player, to);
            if (result) acquired(player, original); return result;
        }
        var category = EquipmentInventoryRules.category(source);
        if (ground != null && category != null && (to == 41 && category == EquipmentInventoryRules.Category.BACKPACK
                || category != EquipmentInventoryRules.Category.BACKPACK && to == 36 + category.slot.getIndex())) {
            ItemStack original = source.copy(); boolean result = ground.exchangeEquipment(player, false);
            if (result) acquired(player, original); return result;
        }
        if (to == 41) return false;
        Plan plan = new Plan(player);
        if (ground == null && from == 41) {
            if (to != -1) return false;
            return removeBackpack(player);
        }
        if (ground == null && from >= 36 && !canRemove(player, source)) return false;
        if (to >= 36 && !canRemove(player, plan.items[to])) return false;
        if (to == -1) {
            if (from < 36) return false;
            plan.items[from] = ItemStack.EMPTY;
            if (!plan.store(source.copy())) return false;
        } else {
            if (!accepts(player, to, source)) return false;
            int amount = to >= 36 || MatchInventoryService.weapon(source) ? 1 : source.getCount();
            ItemStack old = plan.items[to];
            if (ground == null) {
                if (!old.isEmpty() && !accepts(player, from, old)) return false;
                if (amount == source.getCount()) { plan.items[to] = source.copy(); plan.items[from] = old.copy(); }
                else { plan.items[from].shrink(amount); plan.items[to] = source.copyWithCount(amount); if (!plan.store(old.copy())) return false; }
            } else {
                plan.items[to] = source.copyWithCount(amount); if (!plan.store(old.copy())) return false;
                if (!plan.valid() || !ground.consumeForInventory(player, source, amount)) return false;
                plan.commit(); acquired(player, source); return true;
            }
        }
        if (!plan.valid()) return false; plan.commit(); return true;
    }

    private static boolean accepts(Player player, int slot, ItemStack item) {
        if (item.isEmpty()) return true;
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
        ItemStack old = api.getAttachment(player.registryAccess(), gun, type);
        if (old.isEmpty() && !DefaultAssets.isEmptyAttachmentId(api.getAttachmentId(gun, type))) return false;
        ItemStack incoming = unload ? ItemStack.EMPTY : ground == null ? live(player, from) : ground.getItem();
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
        if (!plan.store(old.copy()) || !plan.valid()) return false;
        if (ground != null && !ground.consumeForInventory(player, incoming, 1)) return false;
        plan.commit();
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
                || !DefaultAssets.isEmptyAttachmentId(api.getAttachmentId(gun, type))
                || !preservesAmmunition(player, live(player, source), gun, api)) return false;
        if (target >= 0) {
            ItemStack destination = plan.items[target];
            if (!compatible(destination, attachment, type)) return false;
            IGun destinationApi = (IGun) destination.getItem();
            ItemStack displaced = destinationApi.getAttachment(player.registryAccess(), destination, type).copy();
            if (displaced.isEmpty() && !DefaultAssets.isEmptyAttachmentId(destinationApi.getAttachmentId(destination, type))
                    || ItemStack.isSameItemSameComponents(displaced, attachment)) return false;
            destinationApi.installAttachment(player.registryAccess(), destination, attachment.copy());
            if (!ItemStack.matches(attachment, destinationApi.getAttachment(player.registryAccess(), destination, type))
                    || !preservesAmmunition(player, live(player, target), destination, destinationApi)
                    || !plan.store(displaced)) return false;
        }
        if (!plan.valid() || !plan.stillCurrent()) return false;
        if (target == -2) {
            if (!commitDrop(player, attachment, plan)) return false;
        } else plan.commit();
        sound(player, attachment, true, source);
        if (target >= 0) sound(player, attachment, false, target);
        return true;
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
        return plan.valid() && commitDrop(player, original, plan);
    }

    private static boolean commitDrop(ServerPlayer player, ItemStack original, Plan plan) {
        // Keep vanilla ownership/pickup delay while checking the actual entity spawn result.
        var previousCapture = player.captureDrops(new ArrayList<>());
        net.minecraft.world.entity.item.ItemEntity dropped;
        try { dropped = player.drop(original.copy(), false, false); }
        finally { player.captureDrops(previousCapture); }
        if (dropped == null) return false;
        var toss = new net.neoforged.neoforge.event.entity.item.ItemTossEvent(dropped, player);
        if (net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(toss).isCanceled() || !plan.stillCurrent()) return false;
        dropped = toss.getEntity();
        var data = new net.minecraft.nbt.CompoundTag();
        dropped.addAdditionalSaveData(data);
        int delay = data.getShort("PickupDelay");
        if (!DroppedLootPolicy.legalDelay(delay) || !DroppedLootPolicy.legalQuantity(dropped.getItem().getCount())) return false;
        GroundLootEntity carrier = LootSpawnRegistration.GROUND_LOOT.get().create(player.level());
        if (carrier == null) return false;
        // The authority creates the final carrier immediately; display never waits for pickup eligibility.
        carrier.initializeDrop(dropped.getItem(), player.blockPosition().below(), delay, dropped.getTarget());
        carrier.setPos(player.getX(), player.getY() + .2, player.getZ());
        carrier.setYRot(DroppedLootPolicy.yaw(dropped)); carrier.yRotO = carrier.getYRot();
        carrier.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        carrier.setCustomName(dropped.getCustomName()); carrier.setCustomNameVisible(dropped.isCustomNameVisible());
        GroundLootWorldPhysics.prepareSpawn(carrier);
        try {
            if (!player.level().addFreshEntity(carrier)) { carrier.discard(); return false; }
        } catch (RuntimeException error) {
            carrier.discard();
            com.example.examplemod.ExampleMod.LOGGER.warn("Inventory drop spawn failed; source retained", error);
            return false;
        }
        if (!plan.stillCurrent()) { carrier.discard(); return false; }
        plan.commit(); return true;
    }

    private static final class Plan {
        final ServerPlayer player;
        final ItemStack[] items = new ItemStack[41];
        final ItemStack[] original = new ItemStack[41];
        final ItemStack originalPack;
        ItemStack pack;
        Plan(ServerPlayer player) {
            this.player = player; originalPack = BackpackService.equipped(player).copy(); pack = originalPack.copy();
            for (int i = 0; i < 41; i++) { original[i] = live(player, i).copy(); items[i] = original[i].copy(); }
        }
        boolean store(ItemStack returning) {
            if (returning.isEmpty()) return true;
            MatchInventoryService.prepareStack(player, returning);
            if (MatchInventoryService.weapon(returning)) return false;
            for (int pass = 0; pass < 2; pass++) for (int index = 0; index < 33; index++) {
                int slot = index < 27 ? index + 9 : index - 24;
                if (!accepts(player, slot, returning)) continue;
                ItemStack existing = items[slot];
                if (existing.isEmpty() != (pass == 1) || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, returning)) continue;
                int limit = Math.min(returning.getMaxStackSize(), player.getInventory().getMaxStackSize(returning));
                int amount = Math.min(returning.getCount(), Math.max(0, limit - existing.getCount()));
                if (amount == 0) continue;
                if (existing.isEmpty()) items[slot] = returning.split(amount); else { existing.grow(amount); returning.shrink(amount); }
                if (returning.isEmpty()) return true;
            }
            return false;
        }
        boolean valid() {
            long before = BackpackService.used(player), after = before;
            for (int i = 0; i < 36; i++) after += (long) weight(i, items[i]) - weight(i, live(player, i));
            int tier = pack.getItem() instanceof BackpackItem backpack ? backpack.tier() : 0;
            int capacity = BackpackRules.capacity(tier, items[38].getItem() instanceof ArmorItem);
            if (!PubgInventoryRules.capacityChange(before, after, BackpackService.capacity(player), capacity)) return false;
            int limit = EquipmentInventoryRules.limit(player);
            if (limit > 0) {
                int[] beforeCounts = EquipmentInventoryRules.carried(player), next = beforeCounts.clone();
                for (int i = 0; i < 41; i++) {
                    int[] old = EquipmentInventoryRules.counts(live(player, i)), now = EquipmentInventoryRules.counts(items[i]);
                    for (int c = 0; c < 3; c++) next[c] += now[c] - old[c];
                }
                int[] oldPack = EquipmentInventoryRules.counts(originalPack), nextPack = EquipmentInventoryRules.counts(pack);
                for (int c = 0; c < 3; c++) next[c] += nextPack[c] - oldPack[c];
                for (int c = 0; c < 3; c++) if (next[c] > Math.max(limit, beforeCounts[c])) return false;
            }
            return true;
        }
        boolean stillCurrent() {
            if (!ItemStack.matches(originalPack, BackpackService.equipped(player))) return false;
            for (int i = 0; i < 41; i++) if (!ItemStack.matches(original[i], live(player, i))) return false;
            return true;
        }
        int weight(int slot, ItemStack stack) {
            boolean melee = slot < 9 && InventoryConfig.current(player).hotbar().get(slot).equals("melee")
                    && MatchInventoryService.role(stack).equals("melee")
                    && !InventoryConfig.current(player).capacityOverrides().containsKey(InventoryConfig.key(stack));
            return melee ? 0 : BackpackWeights.weight(player, stack);
        }
        void commit() {
            for (int i = 0; i < 41; i++) if (!ItemStack.matches(items[i], live(player, i))) player.getInventory().setItem(i, items[i]);
            if (!ItemStack.matches(originalPack, pack)) BackpackService.setEquipped(player, pack);
            changed(player);
        }
    }
}
