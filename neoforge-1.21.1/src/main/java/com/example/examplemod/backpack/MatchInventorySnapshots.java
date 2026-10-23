package com.example.examplemod.backpack;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.zones.match.MatchService;
import com.example.examplemod.zones.match.bot.MatchBotService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Originals and the cleared/restored inventory are committed in the SAME player.dat.
 * Restoration replaces the match inventory; it never adds a second copy of originals.
 */
public final class MatchInventorySnapshots {
    private static final String SNAPSHOT = "tacz_pubg_match_inventory";
    private static final String RECOVERY = "tacz_pubg_match_inventory_recovery";
    private static final java.util.Map<UUID, Long> RETRY_AFTER = new java.util.HashMap<>();
    public static final int RECOVERY_SLOTS = 18;
    private MatchInventorySnapshots() {}

    private record TemporarySlot(Container container, int index, ItemStack contents) {}
    private record Live(ListTag inventory, int selected, ItemStack cursor, List<TemporarySlot> temporarySlots) {}

    private static Live capture(ServerPlayer player) {
        List<TemporarySlot> temporary = new ArrayList<>();
        for (var slot : BackpackOwnership.temporaryInputs(player))
            temporary.add(new TemporarySlot(slot.container, slot.getContainerSlot(), slot.getItem().copy()));
        return new Live(player.getInventory().save(new ListTag()), player.getInventory().selected,
                player.containerMenu.getCarried().copy(), temporary);
    }
    private static List<ItemStack> extras(Live live) {
        List<ItemStack> result = new ArrayList<>();
        if (!live.cursor().isEmpty()) result.add(live.cursor().copy());
        for (TemporarySlot slot : live.temporarySlots())
            if (!slot.contents().isEmpty()) result.add(slot.contents().copy());
        return result;
    }
    private static void clear(ServerPlayer player, Live live) {
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        for (TemporarySlot slot : live.temporarySlots()) slot.container().setItem(slot.index(), ItemStack.EMPTY);
    }
    private static void restoreLive(ServerPlayer player, Live live) {
        player.getInventory().load(live.inventory());
        player.getInventory().selected = live.selected();
        player.containerMenu.setCarried(live.cursor().copy());
        for (TemporarySlot slot : live.temporarySlots())
            slot.container().setItem(slot.index(), slot.contents().copy());
    }
    public static void begin(ServerPlayer player, UUID session) {
        if (MatchBotService.isCarpetPlayer(player)) return;
        if (player.getPersistentData().contains(SNAPSHOT) || hasRecovery(player))
            throw new IllegalStateException("Original inventory is awaiting recovery for " + player.getGameProfile().getName());
        Live live = capture(player);
        List<ItemStack> extras = extras(live);
        if (extras.size() > RECOVERY_SLOTS)
            throw new IllegalStateException("Too many temporary crafting items to safely snapshot for " + player.getGameProfile().getName());
        CompoundTag marker = new CompoundTag();
        marker.putInt("Version", 1);
        marker.putUUID("Session", session);
        marker.put("Inventory", live.inventory().copy());
        marker.putInt("Selected", live.selected());
        marker.put("Extras", saveItems(player, extras));
        CompoundTag previousData = player.getPersistentData().copy();
        try {
            player.getPersistentData().put(SNAPSHOT, marker);
            clear(player, live);
            persist(player);
        } catch (RuntimeException error) {
            restoreData(player, previousData);
            restoreLive(player, live);
            player.containerMenu.broadcastFullState();
            throw error;
        }
        // Only close once cursor, crafting and other temporary inputs have been safely escrowed.
        // External chest slots are never touched.
        player.closeContainer();
        player.inventoryMenu.broadcastFullState();
        player.sendSystemMessage(Component.translatable("message.tacz_pubg.inventory.saved"));
    }

    public static void restoreFinished(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) restoreIfStale(player);
    }
    public static void clonePlayer(ServerPlayer original, ServerPlayer replacement) {
        for (String key : List.of(SNAPSHOT, RECOVERY))
            if (original.getPersistentData().contains(key))
                replacement.getPersistentData().put(key, original.getPersistentData().get(key).copy());
    }
    public static void restoreIfStale(ServerPlayer player) {
        if (!player.isAlive() || MatchBotService.isCarpetPlayer(player)) return;
        CompoundTag marker = player.getPersistentData().getCompound(SNAPSHOT);
        if (!player.getPersistentData().contains(SNAPSHOT, Tag.TAG_COMPOUND)) return;
        UUID session = marker.hasUUID("Session") ? marker.getUUID("Session") : null;
        if (session != null && MatchService.matches(player.server).stream().anyMatch(match -> match.sessionId().equals(session))) return;
        long now = System.nanoTime();
        Long retry = RETRY_AFTER.get(player.getUUID());
        if (retry != null && now - retry < 0) return;
        RETRY_AFTER.put(player.getUUID(), now + java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        if (marker.getInt("Version") != 1 || !marker.contains("Inventory", Tag.TAG_LIST)) {
            ExampleMod.LOGGER.error("Cannot restore invalid original inventory for {}", player.getUUID());
            player.sendSystemMessage(Component.translatable("message.tacz_pubg.inventory.restore_failed"));
            return;
        }
        Live matchInventory = capture(player);
        CompoundTag previousData = player.getPersistentData().copy();
        try {
            validateItems(player, marker.getList("Inventory", Tag.TAG_COMPOUND));
            validateItems(player, marker.getList("Extras", Tag.TAG_COMPOUND));
            clear(player, matchInventory);
            player.getInventory().load(marker.getList("Inventory", Tag.TAG_COMPOUND));
            player.getInventory().selected = Math.clamp(marker.getInt("Selected"), 0, 8);
            List<ItemStack> remaining = new ArrayList<>();
            for (ItemStack stack : loadItems(player, marker.getList("Extras", Tag.TAG_COMPOUND))) {
                // Restoration runs after the match is absent, so these are ordinary vanilla transfers.
                player.getInventory().add(stack);
                if (!stack.isEmpty()) remaining.add(stack.copy());
            }
            setRecovery(player, remaining);
            player.getPersistentData().remove(SNAPSHOT);
            com.example.examplemod.inventory.InventoryConfig.normalizeOutsideRound(player);
            persist(player);
        } catch (RuntimeException error) {
            restoreData(player, previousData);
            restoreLive(player, matchInventory);
            ExampleMod.LOGGER.error("Original inventory recovery retained for retry: {}", player.getUUID(), error);
            player.sendSystemMessage(Component.translatable("message.tacz_pubg.inventory.restore_failed"));
            return;
        }
        player.closeContainer();
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
        RETRY_AFTER.remove(player.getUUID());
        player.sendSystemMessage(Component.translatable(hasRecovery(player)
                ? "message.tacz_pubg.inventory.restored_pending" : "message.tacz_pubg.inventory.restored"));
    }
    public static boolean hasRecovery(net.minecraft.world.entity.player.Player player) {
        return player.getPersistentData().contains(RECOVERY, Tag.TAG_LIST)
                && !player.getPersistentData().getList(RECOVERY, Tag.TAG_COMPOUND).isEmpty();
    }
    public static List<ItemStack> recovery(net.minecraft.world.entity.player.Player player) {
        return loadItems(player, player.getPersistentData().getList(RECOVERY, Tag.TAG_COMPOUND));
    }
    /** Recovery never moves onto the cursor: full inventories leave the item in its persisted slot. */
    public static void takeRecovery(ServerPlayer player, int slot) {
        if (MatchService.participantRunning(player.server, player.getUUID())) return;
        List<ItemStack> values = new ArrayList<>(recovery(player));
        if (slot < 0 || slot >= values.size() || values.get(slot).isEmpty()) return;
        ListTag before = player.getInventory().save(new ListTag());
        CompoundTag previousData = player.getPersistentData().copy();
        try {
            ItemStack item = values.get(slot).copy();
            int count = item.getCount();
            player.getInventory().add(item);
            if (count == item.getCount()) { BackpackService.warn(player); return; }
            values.set(slot, item);
            setRecovery(player, values);
            persist(player);
        } catch (RuntimeException error) {
            player.getInventory().load(before);
            restoreData(player, previousData);
            player.sendSystemMessage(Component.translatable("message.tacz_pubg.inventory.restore_failed"));
            ExampleMod.LOGGER.error("Could not commit recovery slot for {}", player.getUUID(), error);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastFullState();
    }
    private static void setRecovery(ServerPlayer player, List<ItemStack> items) {
        ListTag list = saveItems(player, items.stream().filter(stack -> !stack.isEmpty()).toList());
        if (list.isEmpty()) player.getPersistentData().remove(RECOVERY);
        else player.getPersistentData().put(RECOVERY, list);
    }
    private static ListTag saveItems(net.minecraft.world.entity.player.Player player, List<ItemStack> items) {
        ListTag tag = new ListTag();
        for (ItemStack item : items) tag.add(item.saveOptional(player.registryAccess()));
        return tag;
    }
    private static List<ItemStack> loadItems(net.minecraft.world.entity.player.Player player, ListTag items) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++)
            result.add(ItemStack.parseOptional(player.registryAccess(), items.getCompound(i)));
        return result;
    }
    private static void validateItems(ServerPlayer player, ListTag items) {
        for (int i = 0; i < items.size(); i++)
            if (ItemStack.parse(player.registryAccess(), items.getCompound(i)).isEmpty())
                throw new IllegalStateException("A saved original item is unavailable; recovery record retained");
    }
    private static void restoreData(ServerPlayer player, CompoundTag previous) {
        CompoundTag data = player.getPersistentData();
        for (String key : new ArrayList<>(data.getAllKeys())) data.remove(key);
        data.merge(previous);
    }
    /** Vanilla PlayerDataStorage swallows save errors; a round transition must detect them. */
    private static void persist(ServerPlayer player) {
        Path directory = player.server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Path destination = directory.resolve(player.getStringUUID() + ".dat");
        try {
            CompoundTag data = player.saveWithoutId(new CompoundTag());
            AtomicPlayerFile.commit(destination, data);
        } catch (Exception error) {
            throw new IllegalStateException("Could not durably save original inventory for " + player.getGameProfile().getName(), error);
        }
        // Refresh the integrated server's cached owner NBT through its normal virtual save hook.
        try {
            ((com.example.examplemod.backpack.mixin.PlayerListSaveAccess) player.server.getPlayerList()).taczPubg$savePlayer(player);
        } catch (RuntimeException error) {
            // The durable transaction is already complete. Rolling back only memory here could
            // disagree with player.dat and duplicate originals after the next server crash.
            ExampleMod.LOGGER.error("Player save hook failed after durable inventory commit: {}", player.getUUID(), error);
        }
    }
}
