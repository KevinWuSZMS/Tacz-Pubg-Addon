package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Bounded independent map editor. Every mutation submits exactly one complete map definition. */
public final class MapZoneConfigPayloads {
    public static final int MAX_PAGE_SIZE = 16;
    public static final int PAGE = 0, SAVED = 1, CONFLICT = 2, FAILED = 3, DELETED = 4, ENTITY_RECORDING_DECIDED = 5;
    private static final int MAX_C2S_BYTES = 32 * 1024;
    private static volatile Consumer<Snapshot> clientHandler = ignored -> {};

    private MapZoneConfigPayloads() {}

    public record Summary(long id, String name, ResourceLocation dimension,
            double minX, double minZ, double maxX, double maxZ, boolean hasSafeZone, int bombingZoneCount) {
        public Summary {
            Objects.requireNonNull(name); Objects.requireNonNull(dimension);
            if (id < 1 || name.length() > 80 || dimension.toString().length() > 128
                    || !Double.isFinite(minX) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxZ)
                    || maxX <= minX || maxZ <= minZ || bombingZoneCount < 0 || bombingZoneCount > MapZoneModel.MAX_BOMBING_ZONES) {
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_config_response.invalid_map_summary"));
            }
        }
        public double area() { return (maxX - minX) * (maxZ - minZ); }
    }

    /** detail is nullable; failures/conflicts never imply that a client draft was saved. */
    public record Snapshot(UUID request, long revision, int total, int offset, int pageSize,
            List<Summary> maps, long selectedMapId, MapZoneModel.MapDefinition detail,
            int outcome, String message, String suggestedMapName, boolean entityRecordingPending) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = MapZoneConfigPayloads.type("map_zone_config_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = codec(buffer -> {
            UUID request = buffer.readUUID(); long revision = buffer.readVarLong();
            int total = buffer.readVarInt(), offset = buffer.readVarInt(), pageSize = buffer.readVarInt();
            int outcome = buffer.readVarInt(); String message = buffer.readUtf(Texts.MAX_MESSAGE_LENGTH);
            long selected = buffer.readVarLong(); int count = buffer.readVarInt();
            validatePage(offset, pageSize);
            if (total < 0 || total > MapZoneModel.MAX_MAPS || offset > total || count < 0 || count > pageSize
                    || outcome < PAGE || outcome > ENTITY_RECORDING_DECIDED || revision < -1 || selected < 0) {
                throw new DecoderException(Texts.tr("error.tacz_pubg.map_config_response.invalid_map_page"));
            }
            List<Summary> maps = new ArrayList<>(count);
            for (int i = 0; i < count; i++) maps.add(new Summary(buffer.readVarLong(), buffer.readUtf(80),
                    buffer.readResourceLocation(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readBoolean(), buffer.readVarInt()));
            MapZoneModel.MapDefinition detail = buffer.readBoolean() ? MapZoneModel.parse(readJson(buffer)) : null;
            String suggestedMapName = buffer.readUtf(128);
            return new Snapshot(request, revision, total, offset, pageSize, maps, selected, detail, outcome, message, suggestedMapName, buffer.readBoolean());
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.total); buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize);
            buffer.writeVarInt(packet.outcome); buffer.writeUtf(packet.message, Texts.MAX_MESSAGE_LENGTH);
            buffer.writeVarLong(packet.selectedMapId); buffer.writeVarInt(packet.maps.size());
            for (Summary map : packet.maps) {
                buffer.writeVarLong(map.id); buffer.writeUtf(map.name, 80); buffer.writeResourceLocation(map.dimension);
                buffer.writeDouble(map.minX); buffer.writeDouble(map.minZ); buffer.writeDouble(map.maxX); buffer.writeDouble(map.maxZ);
                buffer.writeBoolean(map.hasSafeZone); buffer.writeVarInt(map.bombingZoneCount);
            }
            buffer.writeBoolean(packet.detail != null);
            if (packet.detail != null) writeJson(buffer, MapZoneModel.toJson(packet.detail));
            buffer.writeUtf(packet.suggestedMapName, 128); buffer.writeBoolean(packet.entityRecordingPending);
        });
        public Snapshot(UUID request, long revision, int total, int offset, int pageSize,
                List<Summary> maps, long selectedMapId, MapZoneModel.MapDefinition detail,
                int outcome, String message) {
            this(request, revision, total, offset, pageSize, maps, selectedMapId, detail, outcome, message,
                    MapDefaultNames.next(maps.stream().map(map -> new MapDefaultNames.Existing(map.id(), map.name())).toList()), false);
        }
        public Snapshot(UUID request, long revision, int total, int offset, int pageSize,
                List<Summary> maps, long selectedMapId, MapZoneModel.MapDefinition detail,
                int outcome, String message, String suggestedMapName) {
            this(request, revision, total, offset, pageSize, maps, selectedMapId, detail, outcome, message, suggestedMapName, false);
        }
        public Snapshot {
            Objects.requireNonNull(request); Objects.requireNonNull(message); Objects.requireNonNull(suggestedMapName);
            maps = List.copyOf(maps);
            validatePage(offset, pageSize);
            if (revision < -1 || total < 0 || total > MapZoneModel.MAX_MAPS || offset > total || maps.size() > pageSize
                    || selectedMapId < 0 || outcome < PAGE || outcome > ENTITY_RECORDING_DECIDED || message.length() > Texts.MAX_MESSAGE_LENGTH
                    || entityRecordingPending && detail == null || suggestedMapName.isBlank() || suggestedMapName.length() > 128) {
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_config_response.invalid_map_snapshot"));
            }
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void setClientHandler(Consumer<Snapshot> handler) { clientHandler = Objects.requireNonNull(handler); }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("8-entity-recording-choice");
        registrar.playToServer(Request.TYPE, Request.CODEC,
                (packet, context) -> onServer(context, packet.request, player -> request(player, packet)));
        registrar.playToServer(Save.TYPE, Save.CODEC,
                (packet, context) -> onServer(context, packet.request, player -> save(player, packet)));
        registrar.playToServer(Delete.TYPE, Delete.CODEC,
                (packet, context) -> onServer(context, packet.request, player -> delete(player, packet)));
        registrar.playToServer(EntityRecordingChoice.TYPE, EntityRecordingChoice.CODEC,
                (packet, context) -> onServer(context, packet.request, player -> chooseEntityRecording(player, packet)));
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> clientHandler.accept(packet)));
    }

    /** revision -1 means an explicit initial/reload request; detailMapId 0 requests only headers. */
    public static void request(UUID request, long revision, int offset, int pageSize, long detailMapId) {
        validateRequest(revision, offset, pageSize, detailMapId);
        PacketDistributor.sendToServer(new Request(request, revision, offset, pageSize, detailMapId));
    }

    public static void save(UUID request, long revision, MapZoneModel.MapDefinition draft, int offset, int pageSize) {
        validateMutation(revision, offset, pageSize);
        PacketDistributor.sendToServer(new Save(request, revision, offset, pageSize, MapZoneModel.toJson(draft)));
    }

    public static void delete(UUID request, long revision, long mapId, int offset, int pageSize) {
        validateMutation(revision, offset, pageSize);
        if (mapId < 1 || mapId == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.enter_valid_map_id"));
        PacketDistributor.sendToServer(new Delete(request, revision, offset, pageSize, mapId));
    }

    public static void chooseEntityRecording(UUID request, long revision, long mapId, boolean record, int offset, int pageSize) {
        validateMutation(revision, offset, pageSize);
        validateChoiceMap(mapId);
        PacketDistributor.sendToServer(new EntityRecordingChoice(request, revision, offset, pageSize, mapId, record));
    }

    private static void validateChoiceMap(long mapId) {
        if (mapId < 1 || mapId == Long.MAX_VALUE)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.enter_valid_map_id"));
    }

    private static void onServer(IPayloadContext context, UUID request, Consumer<ServerPlayer> action) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (player.hasPermissions(2)) action.accept(player);
                else PacketDistributor.sendToPlayer(player, new Snapshot(request, -1, 0, 0, 1, List.of(),
                        0, null, FAILED, Texts.tr("message.tacz_pubg.map_zone_commands.permission_level_required_edit_maps_zones")));
            }
        });
    }

    private static void request(ServerPlayer player, Request packet) {
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        if (!data.loadError().isEmpty()) {
            send(player, packet.request, packet.offset, packet.pageSize, 0, FAILED, data.loadError());
            return;
        }
        if (packet.revision >= 0 && packet.revision != data.revision()) {
            send(player, packet.request, packet.offset, packet.pageSize, packet.detailMapId, CONFLICT,
                    Texts.tr("message.tacz_pubg.map_config_response.map_configuration_has_changed_draft_retained_reload_review"));
            return;
        }
        if (packet.detailMapId > 0) {
            try {
                MapZoneCommands.select(player, packet.detailMapId);
            } catch (IllegalArgumentException | IllegalStateException error) {
                send(player, packet.request, packet.offset, packet.pageSize, 0, FAILED, error.getMessage());
                return;
            }
        }
        send(player, packet.request, packet.offset, packet.pageSize, packet.detailMapId, PAGE,
                Texts.tr("message.tacz_pubg.map_config_response.edit_map_boundaries_zones_routes_after_saving_map"));
    }

    private static void save(ServerPlayer player, Save packet) {
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        if (packet.revision != data.revision()) {
            send(player, packet.request, packet.offset, packet.pageSize, 0, CONFLICT,
                    Texts.tr("message.tacz_pubg.map_config_response.map_version_changed_draft_retained_reload_review_before"));
            return;
        }
        long draftId = 0;
        try {
            var draft = MapZoneModel.parse(packet.json);
            draftId = draft.id();
            validateOwnership(player, data, draft);
            var saved = data.saveMap(packet.revision, draft);
            MapZoneCommands.select(player, saved.id());
            send(player, packet.request, packet.offset, packet.pageSize, saved.id(), SAVED,
                    Texts.tr("message.tacz_pubg.map_config_response.map_saved_map_baseline_restored_before_after_every", saved.id(), com.example.examplemod.backup.MapResetService.description(player.server, saved.id())));
        } catch (IllegalArgumentException | IllegalStateException error) {
            send(player, packet.request, packet.offset, packet.pageSize, draftId, FAILED,
                    Texts.tr("message.tacz_pubg.map_config_response.map_save_notice", error.getMessage()));
        }
    }

    private static void chooseEntityRecording(ServerPlayer player, EntityRecordingChoice packet) {
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        if (packet.revision != data.revision()) {
            send(player, packet.request, packet.offset, packet.pageSize, packet.mapId, CONFLICT,
                    Texts.tr("message.tacz_pubg.map_config_response.entity_recording_choice_stale"));
            return;
        }
        try {
            if (data.map(packet.mapId) == null || !com.example.examplemod.backup.MapResetService.entityRecordingPending(player.server, packet.mapId))
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_config_response.entity_recording_choice_not_pending"));
            com.example.examplemod.backup.MapResetService.chooseEntityRecording(player.server, packet.mapId, packet.record);
            send(player, packet.request, packet.offset, packet.pageSize, packet.mapId, ENTITY_RECORDING_DECIDED,
                    Texts.tr(packet.record ? "message.tacz_pubg.map_config_response.entity_recording_enabled"
                            : "message.tacz_pubg.map_config_response.entity_recording_disabled"));
        } catch (IllegalArgumentException | IllegalStateException error) {
            send(player, packet.request, packet.offset, packet.pageSize, packet.mapId, FAILED, error.getMessage());
        }
    }

    private static void validateOwnership(ServerPlayer player, MapZoneSavedData data, MapZoneModel.MapDefinition draft) {
        if (!player.hasPermissions(2)) throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_config_response.permission_level_required_edit_maps"));
        var old = draft.id() == 0 ? null : data.map(draft.id());
        if (draft.id() > 0 && old == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.select_map_current_map_list_use_id_new", draft.id()));
        if (old == null && !draft.dimension().equals(player.serverLevel().dimension().location())) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.new_map_belong_player_s_current_dimension"));
        }
        if (old != null && !old.dimension().equals(draft.dimension())) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.map_s_dimension_fixed_creation_create_another_map"));
        }
        Set<Long> owned = new HashSet<>();
        if (old != null) old.bombingZones().forEach(zone -> owned.add(zone.id()));
        for (var zone : draft.bombingZones()) {
            if (zone.id() > 0 && !owned.contains(zone.id())) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.check_which_map_bombardment_zone_belongs_new_zones", zone.id()));
            }
        }
    }

    private static void delete(ServerPlayer player, Delete packet) {
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        if (packet.revision != data.revision()) {
            send(player, packet.request, packet.offset, packet.pageSize, packet.mapId, CONFLICT,
                    Texts.tr("message.tacz_pubg.map_config_response.map_version_changed_draft_retained_reload_review_before.delete"));
            return;
        }
        try {
            data.deleteMap(packet.revision, packet.mapId);
            var selected = MapZoneCommands.selectedMap(player);
            send(player, packet.request, packet.offset, packet.pageSize, selected == null ? 0 : selected.id(), DELETED,
                    Texts.tr("message.tacz_pubg.map_config_response.map_safe_bombardment_zone_definitions_deleted_terrain_loot", packet.mapId));
        } catch (IllegalArgumentException | IllegalStateException error) {
            send(player, packet.request, packet.offset, packet.pageSize, packet.mapId, FAILED,
                    Texts.tr("message.tacz_pubg.map_config_response.map_deletion_notice", error.getMessage()));
        }
    }

    private static void send(ServerPlayer player, UUID request, int offset, int pageSize, long detailMapId,
            int outcome, String message) {
        MapZoneSavedData data = MapZoneSavedData.get(player.server);
        List<MapZoneModel.MapDefinition> maps = data.maps();
        int start = maps.isEmpty() ? 0 : Math.min(offset, (maps.size() - 1) / pageSize * pageSize);
        List<Summary> headers = maps.subList(start, Math.min(maps.size(), start + pageSize)).stream()
                .map(map -> new Summary(map.id(), map.name(), map.dimension(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                        map.safeZone() != null, map.bombingZones().size())).toList();
        var selected = MapZoneCommands.selectedMap(player);
        if (!data.loadError().isEmpty()) { outcome = FAILED; message = Texts.tr("message.tacz_pubg.map_config_response.original_map_data_protected_editing_disabled", data.loadError()); }
        PacketDistributor.sendToPlayer(player, new Snapshot(request, data.revision(), maps.size(), start, pageSize,
                headers, selected == null ? 0 : selected.id(), detailMapId > 0 ? data.map(detailMapId) : null,
                outcome, shortMessage(message),
                MapDefaultNames.next(maps.stream().map(map -> new MapDefaultNames.Existing(map.id(), map.name())).toList()),
                detailMapId > 0 && data.map(detailMapId) != null && com.example.examplemod.backup.MapResetService.entityRecordingPending(player.server, detailMapId)));
    }

    private static String shortMessage(String value) {
        String text = value == null ? "" : value;
        return Texts.limit(text, Texts.MAX_MESSAGE_LENGTH);
    }

    private static void validatePage(int offset, int pageSize) {
        if (offset < 0 || offset > MapZoneModel.MAX_MAPS || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_config_response.invalid_map_page_request"));
        }
    }

    private static void validateRequest(long revision, int offset, int pageSize, long detailMapId) {
        validatePage(offset, pageSize);
        if (revision < -1 || detailMapId < 0 || detailMapId == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_config_response.invalid_map_request"));
    }

    private static void validateMutation(long revision, int offset, int pageSize) {
        validatePage(offset, pageSize);
        if (revision < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.read_map_before_saving_deleting"));
    }

    private static void validateC2SSize(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_C2S_BYTES) throw new DecoderException(Texts.tr("error.tacz_pubg.map_config_response.map_packet_exceeds_32_kib"));
    }

    private static String readJson(RegistryFriendlyByteBuf buffer) {
        String json = buffer.readUtf(MapZoneModel.MAX_JSON_BYTES);
        if (json.getBytes(StandardCharsets.UTF_8).length > MapZoneModel.MAX_JSON_BYTES) {
            throw new DecoderException(Texts.tr("error.tacz_pubg.map_config_response.map_json_exceeds_24_kib"));
        }
        return json;
    }

    private static void writeJson(RegistryFriendlyByteBuf buffer, String json) {
        if (json.length() > MapZoneModel.MAX_JSON_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MapZoneModel.MAX_JSON_BYTES) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.map_config_response.single_map_json_exceeds_kib"));
        }
        buffer.writeUtf(json, MapZoneModel.MAX_JSON_BYTES);
    }

    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(Function<RegistryFriendlyByteBuf, T> reader,
            BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        return new StreamCodec<>() {
            @Override public T decode(RegistryFriendlyByteBuf buffer) { return reader.apply(buffer); }
            @Override public void encode(RegistryFriendlyByteBuf buffer, T value) { writer.accept(buffer, value); }
        };
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", path));
    }

    private record Request(UUID request, long revision, int offset, int pageSize, long detailMapId) implements CustomPacketPayload {
        private static final Type<Request> TYPE = MapZoneConfigPayloads.type("map_zone_config_request");
        private static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = codec(buffer -> {
            validateC2SSize(buffer);
            UUID request = buffer.readUUID(); long revision = buffer.readVarLong();
            int offset = buffer.readVarInt(), pageSize = buffer.readVarInt(); long detail = buffer.readVarLong();
            validateRequest(revision, offset, pageSize, detail);
            return new Request(request, revision, offset, pageSize, detail);
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize); buffer.writeVarLong(packet.detailMapId);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record Save(UUID request, long revision, int offset, int pageSize, String json) implements CustomPacketPayload {
        private static final Type<Save> TYPE = MapZoneConfigPayloads.type("map_zone_config_save");
        private static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = codec(buffer -> {
            validateC2SSize(buffer);
            UUID request = buffer.readUUID(); long revision = buffer.readVarLong();
            int offset = buffer.readVarInt(), pageSize = buffer.readVarInt();
            validateMutation(revision, offset, pageSize);
            return new Save(request, revision, offset, pageSize, readJson(buffer));
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize); writeJson(buffer, packet.json);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record EntityRecordingChoice(UUID request, long revision, int offset, int pageSize, long mapId, boolean record) implements CustomPacketPayload {
        public static final Type<EntityRecordingChoice> TYPE = MapZoneConfigPayloads.type("map_zone_entity_recording_choice");
        public static final StreamCodec<RegistryFriendlyByteBuf, EntityRecordingChoice> CODEC = codec(buffer -> {
            validateC2SSize(buffer);
            return new EntityRecordingChoice(buffer.readUUID(), buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong(), buffer.readBoolean());
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision); buffer.writeVarInt(packet.offset);
            buffer.writeVarInt(packet.pageSize); buffer.writeVarLong(packet.mapId); buffer.writeBoolean(packet.record);
        });
        public EntityRecordingChoice {
            Objects.requireNonNull(request);
            validateMutation(revision, offset, pageSize); validateChoiceMap(mapId);
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private record Delete(UUID request, long revision, int offset, int pageSize, long mapId) implements CustomPacketPayload {
        private static final Type<Delete> TYPE = MapZoneConfigPayloads.type("map_zone_config_delete");
        private static final StreamCodec<RegistryFriendlyByteBuf, Delete> CODEC = codec(buffer -> {
            validateC2SSize(buffer);
            UUID request = buffer.readUUID(); long revision = buffer.readVarLong();
            int offset = buffer.readVarInt(), pageSize = buffer.readVarInt(); long id = buffer.readVarLong();
            validateMutation(revision, offset, pageSize);
            if (id < 1 || id == Long.MAX_VALUE) throw new DecoderException(Texts.tr("error.tacz_pubg.map_config_response.invalid_map_delete_id"));
            return new Delete(request, revision, offset, pageSize, id);
        }, (buffer, packet) -> {
            buffer.writeUUID(packet.request); buffer.writeVarLong(packet.revision);
            buffer.writeVarInt(packet.offset); buffer.writeVarInt(packet.pageSize); buffer.writeVarLong(packet.mapId);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
