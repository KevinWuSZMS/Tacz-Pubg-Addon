package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Independent, bounded region metadata editor. Floor coordinates never travel C2S. */
public final class SpawnRegionConfigPayloads {
    public static final int MAX_PAGE_SIZE = 64;
    // 1024 varlong IDs plus doubles remain below the C2S 32 KiB packet limit.
    public static final int MAX_CHANGES = 1024;
    public static final int PAGE = 0, SAVED = 1, CONFLICT = 2, FAILED = 3;
    private static volatile Consumer<Snapshot> clientHandler = ignored -> {};

    private SpawnRegionConfigPayloads() {}

    public record Summary(long id, ResourceLocation dimension, int area, BlockPos min,
            BlockPos max, double rarity) {
        public Summary {
            Objects.requireNonNull(dimension);
            min = Objects.requireNonNull(min).immutable();
            max = Objects.requireNonNull(max).immutable();
            if (id < 1 || area < 1 || !Double.isFinite(rarity) || rarity < 0 || rarity > 1000) {
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_region_config_payloads.invalid_region_summary"));
            }
        }
    }

    public record Snapshot(UUID request, long revision, int total, int offset, int pageSize,
            List<Summary> regions, int outcome, String message) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = SpawnRegionConfigPayloads.type("spawn_region_config_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = codec(buffer -> {
            UUID request = buffer.readUUID();
            long revision = buffer.readVarLong();
            int total = buffer.readVarInt(), offset = buffer.readVarInt(), pageSize = buffer.readVarInt();
            int outcome = buffer.readVarInt();
            String message = buffer.readUtf(Texts.MAX_MESSAGE_LENGTH);
            int size = buffer.readVarInt();
            if (total < 0 || total > SpawnAreaSavedData.MAX_TOTAL_FLOORS || offset < 0 || offset > total
                    || pageSize < 1 || pageSize > MAX_PAGE_SIZE || size < 0 || size > pageSize
                    || outcome < PAGE || outcome > FAILED) throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_region_config_payloads.invalid_region_page"));
            List<Summary> regions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                regions.add(new Summary(buffer.readVarLong(), buffer.readResourceLocation(), buffer.readVarInt(),
                        buffer.readBlockPos(), buffer.readBlockPos(), buffer.readDouble()));
            }
            return new Snapshot(request, revision, total, offset, pageSize, regions, outcome, message);
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.total); buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize);
            buffer.writeVarInt(packet.outcome); buffer.writeUtf(packet.message, Texts.MAX_MESSAGE_LENGTH);
            buffer.writeVarInt(packet.regions.size());
            for (Summary region : packet.regions) {
                buffer.writeVarLong(region.id); buffer.writeResourceLocation(region.dimension);
                buffer.writeVarInt(region.area); buffer.writeBlockPos(region.min); buffer.writeBlockPos(region.max);
                buffer.writeDouble(region.rarity);
            }
        });
        public Snapshot { regions = List.copyOf(regions); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void setClientHandler(Consumer<Snapshot> handler) {
        clientHandler = Objects.requireNonNull(handler);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2-i18n");
        registrar.playToServer(Request.TYPE, Request.CODEC,
                (packet, context) -> onServer(context, packet.request, player -> request(player, packet)));
        registrar.playToServer(Save.TYPE, Save.CODEC,
                (packet, context) -> onServer(context, packet.request, player -> save(player, packet)));
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> clientHandler.accept(packet)));
    }

    public static void request(UUID request, long revision, int offset, int pageSize) {
        validatePage(offset, pageSize);
        PacketDistributor.sendToServer(new Request(request, revision, offset, pageSize));
    }

    public static void save(UUID request, long revision, Map<Long, Double> changes, int offset, int pageSize) {
        validatePage(offset, pageSize);
        if (changes.size() > MAX_CHANGES) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.spawn_region_config_payloads.most_regions_can_modified_per_submission_save_before", MAX_CHANGES));
        for (var entry : changes.entrySet()) {
            if (entry.getKey() < 1 || !Double.isFinite(entry.getValue()) || entry.getValue() < 0 || entry.getValue() > 1000) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.spawn_region_config_payloads.rarity_multiplier_finite_between"));
            }
        }
        PacketDistributor.sendToServer(new Save(request, revision, offset, pageSize, changes));
    }

    private static void onServer(IPayloadContext context, UUID request, Consumer<ServerPlayer> action) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (player.hasPermissions(2)) action.accept(player);
                else PacketDistributor.sendToPlayer(player, new Snapshot(request, -1, 0, 0, 1,
                        List.of(), FAILED, Texts.tr("message.tacz_pubg.spawn_region_config_payloads.permission_level_required_edit_loot_regions")));
            }
        });
    }

    private static void request(ServerPlayer player, Request packet) {
        SpawnAreaSavedData data = SpawnAreaSavedData.get(player.server);
        boolean conflict = packet.revision >= 0 && packet.revision != data.revision();
        send(player, packet.request, packet.offset, packet.pageSize, conflict ? CONFLICT : PAGE,
                conflict ? Texts.tr("message.tacz_pubg.spawn_region_config_payloads.regions_rarity_multipliers_have_changed_draft_retained_reload") : Texts.tr("message.tacz_pubg.spawn_region_config_payloads.region_multipliers_saved_independently_save_density_parent_page"));
    }

    private static void save(ServerPlayer player, Save packet) {
        SpawnAreaSavedData data = SpawnAreaSavedData.get(player.server);
        if (packet.revision != data.revision()) {
            send(player, packet.request, packet.offset, packet.pageSize, CONFLICT,
                    Texts.tr("message.tacz_pubg.spawn_region_config_payloads.region_rarity_multiplier_version_has_changed_draft_retained"));
            return;
        }
        try {
            data.setRarities(packet.revision, packet.changes);
            send(player, packet.request, packet.offset, packet.pageSize, SAVED,
                    Texts.tr("message.tacz_pubg.spawn_region_config_payloads.region_multipliers_saved_world_loot_draft_retained_run"));
        } catch (IllegalArgumentException | IllegalStateException error) {
            send(player, packet.request, packet.offset, packet.pageSize, FAILED,
                    Texts.tr("message.tacz_pubg.spawn_region_config_payloads.region_multiplier_save_notice", error.getMessage()));
        }
    }

    private static void send(ServerPlayer player, UUID request, int offset, int pageSize, int outcome, String message) {
        SpawnAreaSavedData data = SpawnAreaSavedData.get(player.server);
        List<SpawnAreaSavedData.Region> regions = data.regions();
        int start = regions.isEmpty() ? 0 : Math.min(offset, (regions.size() - 1) / pageSize * pageSize);
        List<Summary> page = regions.subList(start, Math.min(regions.size(), start + pageSize)).stream()
                .map(region -> new Summary(region.id(), region.dimension(), region.area(), region.min(), region.max(), region.rarity()))
                .toList();
        String text = message == null ? "" : message;
        text = Texts.limit(text, Texts.MAX_MESSAGE_LENGTH);
        PacketDistributor.sendToPlayer(player, new Snapshot(request, data.revision(), regions.size(), start, pageSize, page, outcome, text));
    }

    private static void validatePage(int offset, int pageSize) {
        if (offset < 0 || offset > SpawnAreaSavedData.MAX_TOTAL_FLOORS || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_region_config_payloads.invalid_region_page_request"));
        }
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

    private record Request(UUID request, long revision, int offset, int pageSize) implements CustomPacketPayload {
        private static final Type<Request> TYPE = SpawnRegionConfigPayloads.type("spawn_region_config_request");
        private static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = codec(buffer -> {
            UUID request = buffer.readUUID(); long revision = buffer.readVarLong();
            int offset = buffer.readVarInt(), pageSize = buffer.readVarInt();
            validatePage(offset, pageSize);
            return new Request(request, revision, offset, pageSize);
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record Save(UUID request, long revision, int offset, int pageSize,
            Map<Long, Double> changes) implements CustomPacketPayload {
        private static final Type<Save> TYPE = SpawnRegionConfigPayloads.type("spawn_region_config_save");
        private static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = codec(buffer -> {
            UUID request = buffer.readUUID(); long revision = buffer.readVarLong();
            int offset = buffer.readVarInt(), pageSize = buffer.readVarInt();
            validatePage(offset, pageSize);
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_CHANGES) throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_region_config_payloads.too_many_region_coefficient_changes"));
            Map<Long, Double> changes = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                long id = buffer.readVarLong(); double rarity = buffer.readDouble();
                if (id < 1 || !Double.isFinite(rarity) || rarity < 0 || rarity > 1000 || changes.put(id, rarity) != null) {
                    throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_region_config_payloads.invalid_or_duplicated_region_coefficient"));
                }
            }
            return new Save(request, revision, offset, pageSize, changes);
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize); buffer.writeVarInt(packet.changes.size());
            for (var change : packet.changes.entrySet()) { buffer.writeVarLong(change.getKey()); buffer.writeDouble(change.getValue()); }
        });
        private Save { changes = Map.copyOf(changes); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
