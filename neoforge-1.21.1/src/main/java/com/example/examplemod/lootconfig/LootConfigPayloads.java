package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.mojang.logging.LogUtils;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Bounded, revision-checked whole-draft transfers. No partial draft reaches the live store. */
public final class LootConfigPayloads {
    public static final int CHUNK_CHARACTERS = 6_000;
    public static final int MAX_DOCUMENT_PARTS = 1500;
    private static final int MAX_CATALOG_PARTS = Integer.MAX_VALUE;
    private static final int MAX_CHOICES_PER_PART = 128;
    private static final int MAX_CATALOG_PACKET_BYTES = 256 * 1024;
    private static final int MAX_CATALOG_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final long SESSION_NANOS = 30_000_000_000L;
    private static final Map<MinecraftServer, Map<UUID, Upload>> UPLOADS = new WeakHashMap<>();
    private static final Map<UUID, Download> DOWNLOADS = new HashMap<>();
    private static volatile Consumer<Snapshot> clientHandler = ignored -> {};

    private LootConfigPayloads() {}

    public record Snapshot(long revision, LootConfigModel.Document document, List<LootCatalog.Choice> catalog,
            boolean success, boolean openScreen, String message) {
        public Snapshot {
            catalog = List.copyOf(catalog);
        }
    }

    public static void setClientHandler(Consumer<Snapshot> handler) {
        clientHandler = Objects.requireNonNull(handler);
    }

    public static void clearClientTransfers() {
        DOWNLOADS.clear();
    }

    /** Client entry point; each C2S part is at most 6000 chars / 18000 UTF-8 bytes plus headers. */
    public static void submit(LootConfigModel.Document draft, long baseRevision) {
        List<String> errors = LootConfigModel.validate(draft);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        String json = LootConfigModel.toJson(draft);
        int bytes = utf8Size(json);
        if (bytes > LootConfigModel.MAX_JSON_BYTES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.configuration_exceeds_mib_transfer_budget"));
        List<String> parts = split(json);
        UUID id = UUID.randomUUID();
        PacketDistributor.sendToServer(new UploadBegin(id, baseRevision, parts.size(), bytes));
        for (int i = 0; i < parts.size(); i++) PacketDistributor.sendToServer(new UploadPart(id, i, parts.get(i)));
        PacketDistributor.sendToServer(new UploadCommit(id));
    }

    public static void sendSave(long baseRevision, LootConfigModel.Document draft) {
        submit(draft, baseRevision);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("14-i18n");
        registrar.playToServer(UploadBegin.TYPE, UploadBegin.CODEC,
                (packet, context) -> server(context, player -> beginUpload(player, packet)));
        registrar.playToServer(UploadPart.TYPE, UploadPart.CODEC,
                (packet, context) -> server(context, player -> receiveUploadPart(player, packet)));
        registrar.playToServer(UploadCommit.TYPE, UploadCommit.CODEC,
                (packet, context) -> server(context, player -> commitUpload(player, packet)));
        registrar.playToClient(DownloadBegin.TYPE, DownloadBegin.CODEC,
                (packet, context) -> context.enqueueWork(() -> beginDownload(packet)));
        registrar.playToClient(DocumentPart.TYPE, DocumentPart.CODEC,
                (packet, context) -> context.enqueueWork(() -> receiveDocumentPart(packet)));
        registrar.playToClient(CatalogPart.TYPE, CatalogPart.CODEC,
                (packet, context) -> context.enqueueWork(() -> receiveCatalogPart(packet)));
        registrar.playToClient(DownloadComplete.TYPE, DownloadComplete.CODEC,
                (packet, context) -> context.enqueueWork(() -> completeDownload(packet)));
    }

    public static void open(ServerPlayer player) {
        if (!authorized(player)) return;
        LootConfigStore store = LootConfigStore.get(player.serverLevel());
        sendSnapshot(player, store.loadError().isEmpty(), true,
                store.loadError().isEmpty() ? Texts.tr("message.tacz_pubg.loot_config_payloads.you_editing_server_s_saved_configuration_click_save") : store.loadError());
    }

    private static void server(IPayloadContext context, Consumer<ServerPlayer> action) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && authorized(player)) action.accept(player);
        });
    }

    private static boolean authorized(ServerPlayer player) {
        if (player.hasPermissions(2)) return true;
        player.sendSystemMessage(Texts.of("message.tacz_pubg.loot_config_payloads.permission_level_required_edit_loot_configuration"));
        return false;
    }

    private static Map<UUID, Upload> uploads(ServerPlayer player) {
        Map<UUID, Upload> uploads = UPLOADS.computeIfAbsent(player.server, ignored -> new HashMap<>());
        uploads.entrySet().removeIf(entry -> expired(entry.getValue().started));
        return uploads;
    }

    private static void beginUpload(ServerPlayer player, UploadBegin packet) {
        Map<UUID, Upload> uploads = uploads(player);
        uploads.remove(player.getUUID());
        LootConfigStore store = LootConfigStore.get(player.serverLevel());
        if (packet.parts < 1 || packet.parts > MAX_DOCUMENT_PARTS
                || packet.bytes < 1 || packet.bytes > LootConfigModel.MAX_JSON_BYTES) {
            sendSnapshot(player, false, false, Texts.tr("message.tacz_pubg.loot_config_payloads.upload_exceeds_length_chunk_count_limit"));
        } else if (packet.revision != store.revision()) {
            sendSnapshot(player, false, false, Texts.tr("message.tacz_pubg.loot_config_payloads.configuration_version_has_changed_reload_review_merge_your"));
        } else if (uploads.size() >= 32) {
            sendSnapshot(player, false, false, Texts.tr("message.tacz_pubg.loot_config_payloads.too_many_editors_uploading_try_again_later"));
        } else {
            uploads.put(player.getUUID(), new Upload(packet));
        }
    }

    private static void receiveUploadPart(ServerPlayer player, UploadPart packet) {
        Map<UUID, Upload> uploads = UPLOADS.get(player.server);
        Upload upload = uploads == null ? null : uploads.get(player.getUUID());
        if (upload == null || !upload.header.id.equals(packet.id)) return;
        int bytes = utf8Size(packet.text);
        if (expired(upload.started) || packet.index != upload.next || upload.next >= upload.header.parts
                || upload.bytes + bytes > upload.header.bytes) {
            uploads.remove(player.getUUID());
            sendSnapshot(player, false, false, Texts.tr("message.tacz_pubg.loot_config_payloads.check_upload_deadline_chunk_completeness_declared_length_saved"));
            return;
        }
        upload.json.append(packet.text);
        upload.bytes += bytes;
        upload.next++;
    }

    private static void commitUpload(ServerPlayer player, UploadCommit packet) {
        Map<UUID, Upload> uploads = UPLOADS.get(player.server);
        Upload upload = uploads == null ? null : uploads.get(player.getUUID());
        if (upload == null || !upload.header.id.equals(packet.id)) return;
        uploads.remove(player.getUUID());
        if (expired(upload.started) || upload.next != upload.header.parts || upload.bytes != upload.header.bytes) {
            sendSnapshot(player, false, false, Texts.tr("message.tacz_pubg.loot_config_payloads.upload_was_interrupted_timed_out_saved_configuration_retained"));
            return;
        }
        try {
            LootConfigModel.Document document = LootConfigModel.parse(upload.json.toString());
            LootConfigStore.SaveResult result = LootConfigStore.get(player.serverLevel())
                    .save(player.serverLevel(), upload.header.revision, document);
            sendSnapshot(player, result.success(), false, result.message());
        } catch (IllegalArgumentException error) {
            sendSnapshot(player, false, false, Texts.tr("message.tacz_pubg.loot_config_payloads.configuration_validation_failed", error.getMessage()));
        }
    }

    private static void sendSnapshot(ServerPlayer player, boolean success, boolean openScreen, String message) {
        LootConfigStore store = LootConfigStore.get(player.serverLevel());
        List<List<LootCatalog.Choice>> catalog;
        try {
            catalog = catalogParts(player, LootCatalog.build(player.serverLevel()));
        } catch (RuntimeException error) {
            LogUtils.getLogger().error("Unable to build the loot editor catalog", error);
            catalog = List.of();
            message += Texts.tr("message.tacz_pubg.loot_config_payloads.item_catalog_notice", error.getMessage());
        }
        String json = LootConfigModel.toJson(store.document());
        List<String> documentParts = split(json);
        UUID id = UUID.randomUUID();
        int choiceCount = catalog.stream().mapToInt(List::size).sum();
        PacketDistributor.sendToPlayer(player, new DownloadBegin(id, store.revision(), documentParts.size(),
                utf8Size(json), catalog.size(), choiceCount, success, openScreen, shortMessage(message)));
        for (int i = 0; i < documentParts.size(); i++) {
            PacketDistributor.sendToPlayer(player, new DocumentPart(id, i, documentParts.get(i)));
        }
        for (int i = 0; i < catalog.size(); i++) PacketDistributor.sendToPlayer(player, new CatalogPart(id, i, catalog.get(i)));
        PacketDistributor.sendToPlayer(player, new DownloadComplete(id));
    }

    private static List<List<LootCatalog.Choice>> catalogParts(ServerPlayer player, List<LootCatalog.Choice> choices) {
        List<List<LootCatalog.Choice>> parts = new ArrayList<>();
        List<LootCatalog.Choice> current = new ArrayList<>();
        int currentBytes = 64;
        int totalBytes = 0;
        RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(
                Unpooled.buffer(1024, MAX_CATALOG_PACKET_BYTES), player.registryAccess(), ConnectionType.NEOFORGE);
        try {
            for (LootCatalog.Choice choice : choices) {
                scratch.clear();
                writeChoice(scratch, choice);
                int bytes = scratch.readableBytes();
                if (bytes + 64 >= MAX_CATALOG_PACKET_BYTES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.individual_item_preview_too_large", choice.id()));
                totalBytes += bytes;
                if (totalBytes > MAX_CATALOG_TOTAL_BYTES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.total_item_catalog_previews_exceed_mib"));
                if (!current.isEmpty() && (current.size() >= MAX_CHOICES_PER_PART
                        || currentBytes + bytes >= MAX_CATALOG_PACKET_BYTES)) {
                    parts.add(List.copyOf(current));
                    current.clear();
                    currentBytes = 64;
                }
                current.add(choice);
                currentBytes += bytes;
            }
        } finally {
            scratch.release();
        }
        if (!current.isEmpty()) parts.add(List.copyOf(current));
        if (parts.size() > MAX_CATALOG_PARTS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.too_many_item_catalog_chunks"));
        return List.copyOf(parts);
    }

    private static void beginDownload(DownloadBegin packet) {
        DOWNLOADS.entrySet().removeIf(entry -> expired(entry.getValue().started));
        if (packet.documentParts < 1 || packet.documentParts > MAX_DOCUMENT_PARTS
                || packet.documentBytes < 1 || packet.documentBytes > LootConfigModel.MAX_JSON_BYTES
                || packet.catalogParts < 0 || packet.catalogParts > MAX_CATALOG_PARTS
                || packet.catalogCount < 0 || packet.catalogCount > MAX_CATALOG_TOTAL_BYTES / 4) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.server_configuration_snapshot_exceeds_length_limit"));
        }
        if (DOWNLOADS.size() >= 4) DOWNLOADS.clear();
        DOWNLOADS.put(packet.id, new Download(packet));
    }

    private static void receiveDocumentPart(DocumentPart packet) {
        Download download = DOWNLOADS.get(packet.id);
        if (download == null) return;
        int bytes = utf8Size(packet.text);
        if (expired(download.started) || packet.index != download.nextDocument
                || packet.index >= download.header.documentParts
                || download.bytes + bytes > download.header.documentBytes) {
            DOWNLOADS.remove(packet.id);
            return;
        }
        download.json.append(packet.text);
        download.bytes += bytes;
        download.nextDocument++;
    }

    private static void receiveCatalogPart(CatalogPart packet) {
        Download download = DOWNLOADS.get(packet.id);
        if (download == null) return;
        if (expired(download.started) || packet.index != download.nextCatalog
                || packet.index >= download.header.catalogParts
                || download.catalog.size() + packet.choices.size() > download.header.catalogCount) {
            DOWNLOADS.remove(packet.id);
            return;
        }
        download.catalog.addAll(packet.choices);
        download.nextCatalog++;
    }

    private static void completeDownload(DownloadComplete packet) {
        Download download = DOWNLOADS.remove(packet.id);
        if (download == null || expired(download.started)
                || download.nextDocument != download.header.documentParts
                || download.nextCatalog != download.header.catalogParts
                || download.catalog.size() != download.header.catalogCount
                || download.bytes != download.header.documentBytes) return;
        LootConfigModel.Document document = LootConfigModel.parse(download.json.toString());
        DownloadBegin header = download.header;
        clientHandler.accept(new Snapshot(header.revision, document, download.catalog,
                header.success, header.openScreen, header.message));
    }

    // Package-visible for deterministic transfer checks; shared by both network directions.
    static List<String> split(String json) {
        if (utf8Size(json) > LootConfigModel.MAX_JSON_BYTES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.configuration_exceeds_mib_transfer_budget"));
        List<String> result = new ArrayList<>();
        for (int start = 0; start < json.length();) {
            int end = Math.min(start + CHUNK_CHARACTERS, json.length());
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))) end--;
            result.add(json.substring(start, end));
            start = end;
        }
        if (result.isEmpty() || result.size() > MAX_DOCUMENT_PARTS) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_payloads.check_configuration_chunk_count"));
        return List.copyOf(result);
    }

    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean expired(long start) {
        return System.nanoTime() - start > SESSION_NANOS;
    }

    private static String shortMessage(String message) {
        return Texts.limit(message, Texts.MAX_MESSAGE_LENGTH);
    }

    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(Function<RegistryFriendlyByteBuf, T> reader,
            BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf buffer) { return reader.apply(buffer); }
            public void encode(RegistryFriendlyByteBuf buffer, T value) { writer.accept(buffer, value); }
        };
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", path));
    }

    private static void writeChoice(RegistryFriendlyByteBuf buffer, LootCatalog.Choice choice) {
        buffer.writeUtf(choice.kind(), 16);
        buffer.writeUtf(choice.id(), 128);
        ItemStack.STREAM_CODEC.encode(buffer, choice.preview());
        buffer.writeUtf(choice.attachmentInfo().category(), 16);
        buffer.writeVarInt(choice.attachmentInfo().supportedGunCount());
        buffer.writeBoolean(choice.attachmentInfo().specialist());
        buffer.writeBoolean(choice.ammoDefaults() != null);
        if (choice.ammoDefaults() != null) writeAmmoSupply(buffer, choice.ammoDefaults());
        buffer.writeBoolean(choice.bombingAmmo());
        buffer.writeUtf(choice.gunClass(), 16);
        buffer.writeBoolean(choice.heavyWeapon() != null);
        if (choice.heavyWeapon() != null) {
            com.example.examplemod.zones.heavy.HeavyWeaponInfo.STREAM_CODEC.encode(buffer, choice.heavyWeapon());
        }
    }

    private static LootCatalog.Choice readChoice(RegistryFriendlyByteBuf buffer) {
        String kind = buffer.readUtf(16), id = buffer.readUtf(128);
        ItemStack preview = ItemStack.STREAM_CODEC.decode(buffer);
        String category = buffer.readUtf(16);
        int support = buffer.readVarInt();
        boolean specialist = buffer.readBoolean();
        if (!java.util.Set.of("other", "scope", "magazine", "laser", "stock", "muzzle", "grip", "ammo").contains(category)
                || support < 0 || support > 1_000_000) throw new DecoderException(Texts.tr("error.tacz_pubg.loot_config_payloads.invalid_attachment_support_metadata"));
        LootConfigModel.GunAmmoSupply ammoDefaults = buffer.readBoolean() ? readAmmoSupply(buffer) : null;
        if (ammoDefaults != null && !"gun".equals(kind)) throw new DecoderException(Texts.tr("error.tacz_pubg.loot_config_payloads.non_gun_ammo_defaults"));
        boolean bombingAmmo = buffer.readBoolean();
        if (bombingAmmo && !"ammo".equals(kind)) throw new DecoderException(Texts.tr("error.tacz_pubg.loot_config_payloads.non_ammo_bombing_projectile"));
        String gunClass = buffer.readUtf(16);
        com.example.examplemod.zones.heavy.HeavyWeaponInfo heavy = buffer.readBoolean()
                ? com.example.examplemod.zones.heavy.HeavyWeaponInfo.STREAM_CODEC.decode(buffer) : null;
        return new LootCatalog.Choice(kind, id, preview, new AttachmentLootPolicy.Info(category, support, specialist),
                ammoDefaults, bombingAmmo, gunClass, heavy);
    }

    // Exactly three fixed schemes; no untrusted list length can allocate arbitrary memory.
    static void writeAmmoSupply(RegistryFriendlyByteBuf buffer, LootConfigModel.GunAmmoSupply supply) {
        List<String> errors = LootConfigModel.validateGunAmmoSupply(supply);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
        for (LootConfigModel.AmmoOption option : supply.ammoOptions()) {
            buffer.writeUtf(option.ammoId(), 128);
            buffer.writeVarInt(option.groups());
            buffer.writeDouble(option.probability());
        }
        buffer.writeVarInt(supply.roundsPerGroup());
    }

    static LootConfigModel.GunAmmoSupply readAmmoSupply(RegistryFriendlyByteBuf buffer) {
        List<LootConfigModel.AmmoOption> options = new ArrayList<>(LootConfigModel.AMMO_SUPPLY_CHOICES);
        for (int i = 0; i < LootConfigModel.AMMO_SUPPLY_CHOICES; i++)
            options.add(new LootConfigModel.AmmoOption(buffer.readUtf(128), buffer.readVarInt(), buffer.readDouble()));
        int rounds = buffer.readVarInt();
        var supply = new LootConfigModel.GunAmmoSupply(options, rounds);
        List<String> errors = LootConfigModel.validateGunAmmoSupply(supply);
        if (!errors.isEmpty()) throw new DecoderException(String.join("；", errors));
        return supply;
    }

    private record UploadBegin(UUID id, long revision, int parts, int bytes) implements CustomPacketPayload {
        private static final Type<UploadBegin> TYPE = LootConfigPayloads.type("loot_upload_begin");
        private static final StreamCodec<RegistryFriendlyByteBuf, UploadBegin> CODEC = codec(
                b -> new UploadBegin(b.readUUID(), b.readVarLong(), b.readVarInt(), b.readVarInt()),
                (b, p) -> { b.writeUUID(p.id); b.writeVarLong(p.revision); b.writeVarInt(p.parts); b.writeVarInt(p.bytes); });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record UploadPart(UUID id, int index, String text) implements CustomPacketPayload {
        private static final Type<UploadPart> TYPE = LootConfigPayloads.type("loot_upload_part");
        private static final StreamCodec<RegistryFriendlyByteBuf, UploadPart> CODEC = codec(
                b -> new UploadPart(b.readUUID(), b.readVarInt(), b.readUtf(CHUNK_CHARACTERS)),
                (b, p) -> { b.writeUUID(p.id); b.writeVarInt(p.index); b.writeUtf(p.text, CHUNK_CHARACTERS); });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record UploadCommit(UUID id) implements CustomPacketPayload {
        private static final Type<UploadCommit> TYPE = LootConfigPayloads.type("loot_upload_commit");
        private static final StreamCodec<RegistryFriendlyByteBuf, UploadCommit> CODEC = codec(
                b -> new UploadCommit(b.readUUID()), (b, p) -> b.writeUUID(p.id));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record DownloadBegin(UUID id, long revision, int documentParts, int documentBytes,
            int catalogParts, int catalogCount, boolean success, boolean openScreen, String message) implements CustomPacketPayload {
        private static final Type<DownloadBegin> TYPE = LootConfigPayloads.type("loot_download_begin");
        private static final StreamCodec<RegistryFriendlyByteBuf, DownloadBegin> CODEC = codec(
                b -> new DownloadBegin(b.readUUID(), b.readVarLong(), b.readVarInt(), b.readVarInt(),
                        b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readUtf(Texts.MAX_MESSAGE_LENGTH)),
                (b, p) -> { b.writeUUID(p.id); b.writeVarLong(p.revision); b.writeVarInt(p.documentParts);
                    b.writeVarInt(p.documentBytes); b.writeVarInt(p.catalogParts); b.writeVarInt(p.catalogCount);
                    b.writeBoolean(p.success); b.writeBoolean(p.openScreen); b.writeUtf(p.message, Texts.MAX_MESSAGE_LENGTH); });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record DocumentPart(UUID id, int index, String text) implements CustomPacketPayload {
        private static final Type<DocumentPart> TYPE = LootConfigPayloads.type("loot_download_document");
        private static final StreamCodec<RegistryFriendlyByteBuf, DocumentPart> CODEC = codec(
                b -> new DocumentPart(b.readUUID(), b.readVarInt(), b.readUtf(CHUNK_CHARACTERS)),
                (b, p) -> { b.writeUUID(p.id); b.writeVarInt(p.index); b.writeUtf(p.text, CHUNK_CHARACTERS); });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record CatalogPart(UUID id, int index, List<LootCatalog.Choice> choices) implements CustomPacketPayload {
        private static final Type<CatalogPart> TYPE = LootConfigPayloads.type("loot_download_catalog");
        private static final StreamCodec<RegistryFriendlyByteBuf, CatalogPart> CODEC = codec(b -> {
            if (b.readableBytes() >= MAX_CATALOG_PACKET_BYTES) throw new DecoderException(Texts.tr("error.tacz_pubg.loot_config_payloads.loot_catalog_packet_is_too_large"));
            UUID id = b.readUUID();
            int index = b.readVarInt();
            int count = b.readVarInt();
            if (count < 0 || count > MAX_CHOICES_PER_PART) throw new DecoderException(Texts.tr("error.tacz_pubg.loot_config_payloads.invalid_loot_catalog_page_length"));
            List<LootCatalog.Choice> choices = new ArrayList<>(count);
            for (int i = 0; i < count; i++) choices.add(readChoice(b));
            return new CatalogPart(id, index, List.copyOf(choices));
        }, (b, p) -> {
            b.writeUUID(p.id); b.writeVarInt(p.index); b.writeVarInt(p.choices.size());
            for (LootCatalog.Choice choice : p.choices) writeChoice(b, choice);
        });
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record DownloadComplete(UUID id) implements CustomPacketPayload {
        private static final Type<DownloadComplete> TYPE = LootConfigPayloads.type("loot_download_complete");
        private static final StreamCodec<RegistryFriendlyByteBuf, DownloadComplete> CODEC = codec(
                b -> new DownloadComplete(b.readUUID()), (b, p) -> b.writeUUID(p.id));
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static final class Upload {
        private final UploadBegin header;
        private final long started = System.nanoTime();
        private final StringBuilder json = new StringBuilder();
        private int next;
        private int bytes;
        private Upload(UploadBegin header) { this.header = header; }
    }

    private static final class Download {
        private final DownloadBegin header;
        private final long started = System.nanoTime();
        private final StringBuilder json = new StringBuilder();
        private final List<LootCatalog.Choice> catalog = new ArrayList<>();
        private int nextDocument;
        private int nextCatalog;
        private int bytes;
        private Download(DownloadBegin header) { this.header = header; }
    }
}
