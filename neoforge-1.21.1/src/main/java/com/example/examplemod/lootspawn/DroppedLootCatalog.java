package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigStore;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** World-local saved item entries; client and integrated-server snapshots stay separate. */
public final class DroppedLootCatalog {
    public static final int PART_CHARACTERS = 6000;
    public static final int MAX_PARTS = 1500;
    private static final Gson JSON = new Gson();
    private static final Map<MinecraftServer, Snapshot> SERVERS = new WeakHashMap<>();
    private static volatile Set<String> clientItems = Set.of();
    private static long clientRevision = -1;
    private static Assembly incoming;

    private DroppedLootCatalog() {}

    public static void register(IEventBus bus) {
        bus.addListener((ServerStartedEvent event) -> snapshot(event.getServer().overworld()));
        bus.addListener((ServerStoppedEvent event) -> SERVERS.remove(event.getServer()));
        bus.addListener(DroppedLootCatalog::datapackSync);
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) send(player, snapshot(player.serverLevel()));
        });
    }

    public static boolean contains(ItemStack stack, Level level) {
        Set<String> items = level instanceof ServerLevel server ? snapshot(server).items : clientItems;
        return items.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /** Weight zero still registers drop behavior; picker choices not saved here do not. */
    public static Set<String> itemIds(LootConfigModel.Document document) {
        Set<String> items = new HashSet<>();
        for (var entry : document.items()) if ("item".equals(entry.kind())) {
            ResourceLocation id = ResourceLocation.tryParse(entry.id());
            if (id != null) items.add(id.toString());
        }
        return Set.copyOf(items);
    }

    public static void configurationSaved(ServerLevel level) {
        Snapshot snapshot = snapshot(level);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) send(player, snapshot);
    }

    private static Snapshot snapshot(ServerLevel level) {
        LootConfigStore store = LootConfigStore.get(level);
        Snapshot cached = SERVERS.get(level.getServer());
        if (cached != null && cached.revision == store.revision()) return cached;
        Set<String> ids = store.loadError().isEmpty() ? itemIds(store.document()) : Set.of();
        String json = JSON.toJson(ids.stream().sorted().toList());
        if (json.getBytes(StandardCharsets.UTF_8).length > LootConfigModel.MAX_JSON_BYTES)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_catalog.dropped_item_catalog_exceeds_configuration_transfer_budget"));
        Snapshot built = new Snapshot(store.revision(), ids, json);
        SERVERS.put(level.getServer(), built);
        return built;
    }

    private static void datapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        Snapshot snapshot = snapshot(server.overworld());
        if (event.getPlayer() != null) send(event.getPlayer(), snapshot);
        else for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player, snapshot);
    }

    private static void send(ServerPlayer player, Snapshot snapshot) {
        int parts = Math.max(1, (snapshot.json.length() + PART_CHARACTERS - 1) / PART_CHARACTERS);
        if (parts > MAX_PARTS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_catalog.dropped_item_catalog_chunks_exceed_transfer_budget"));
        UUID id = UUID.randomUUID();
        for (int index = 0; index < parts; index++) {
            int start = index * PART_CHARACTERS;
            PacketDistributor.sendToPlayer(player, new GroundLootPayloads.DropCatalogPart(id, snapshot.revision,
                    index, parts, snapshot.json.substring(start, Math.min(start + PART_CHARACTERS, snapshot.json.length()))));
        }
    }

    public static void clearClient() {
        incoming = null;
        clientItems = Set.of();
        clientRevision = -1;
    }

    /** Main client thread only. Commit once all bounded parts form a valid snapshot. */
    public static void receive(GroundLootPayloads.DropCatalogPart packet) {
        if (packet.parts() < 1 || packet.parts() > MAX_PARTS || packet.revision() < 0
                || packet.revision() < clientRevision
                || packet.index() < 0 || packet.index() >= packet.parts()) return;
        if (packet.index() == 0) incoming = new Assembly(packet);
        Assembly pending = incoming;
        if (pending == null || !pending.header.id().equals(packet.id())
                || pending.header.revision() != packet.revision() || pending.header.parts() != packet.parts()
                || pending.next != packet.index() || System.nanoTime() - pending.started > 30_000_000_000L) {
            incoming = null;
            return;
        }
        pending.bytes += packet.text().getBytes(StandardCharsets.UTF_8).length;
        if (pending.bytes > LootConfigModel.MAX_JSON_BYTES) { incoming = null; return; }
        pending.json.append(packet.text());
        if (++pending.next < packet.parts()) return;
        incoming = null;
        try (JsonReader reader = new JsonReader(new StringReader(pending.json.toString()))) {
            reader.setLenient(false);
            Set<String> ids = new HashSet<>();
            reader.beginArray();
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_catalog.invalid_catalog_id_format"));
                ResourceLocation id = ResourceLocation.tryParse(reader.nextString());
                if (id == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_catalog.invalid_catalog_id"));
                ids.add(id.toString());
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.dropped_loot_catalog.unexpected_content_after_catalog"));
            clientItems = Set.copyOf(ids);
            clientRevision = packet.revision();
        } catch (RuntimeException | IOException error) {
            ExampleMod.LOGGER.warn("Rejected incomplete/invalid client drop catalog; previous snapshot retained", error);
        }
    }

    private record Snapshot(long revision, Set<String> items, String json) {}
    private static final class Assembly {
        final GroundLootPayloads.DropCatalogPart header;
        final StringBuilder json = new StringBuilder();
        final long started = System.nanoTime();
        int next;
        int bytes;
        Assembly(GroundLootPayloads.DropCatalogPart header) { this.header = header; }
    }
}
