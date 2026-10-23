package com.example.examplemod.zones;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Read-only presentation channel; it grants no editing permission or gameplay authority. */
public final class MapOverlayPayloads {
    public static final int MAX_TEAMMATES = 256;
    private static Consumer<MapSnapshot> mapHandler = ignored -> {};
    private static Consumer<CatalogPart> catalogHandler = ignored -> {};
    /** One bounded packet per map; clients publish the catalog only after the entire revision arrives. */
    public record CatalogPart(long revision, int index, int count, MapSnapshot map) implements CustomPacketPayload {
        public static final Type<CatalogPart> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "map_catalog"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CatalogPart> CODEC = new StreamCodec<>() {
            public CatalogPart decode(RegistryFriendlyByteBuf b) {
                long revision = b.readVarLong(); int index = b.readVarInt(), count = b.readVarInt();
                return new CatalogPart(revision, index, count, count == 0 ? null : MapSnapshot.CODEC.decode(b));
            }
            public void encode(RegistryFriendlyByteBuf b, CatalogPart p) {
                b.writeVarLong(p.revision); b.writeVarInt(p.index); b.writeVarInt(p.count);
                if (p.count != 0) MapSnapshot.CODEC.encode(b, p.map);
            }
        };
        public CatalogPart {
            if (revision < 0 || count < 0 || count > MapZoneModel.MAX_MAPS || index < 0
                    || index >= Math.max(1, count) || (count != 0 && (map == null || map.json().isEmpty())))
                throw new IllegalArgumentException("Invalid map catalog part");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setCatalogHandler(Consumer<CatalogPart> handler) { catalogHandler = handler; }
    private static Consumer<TeamSnapshot> teamHandler = ignored -> {};
    private MapOverlayPayloads() {}

    /** Current blue boundary and announced safe circle travel together, independently of the transient HUD. */
    public record MapSnapshot(long sequence, ResourceLocation dimension, String json, boolean running,
            MapZoneModel.Circle nextCircle, double flightProgress,
            com.example.examplemod.zones.match.MatchStatus matchStatus) implements CustomPacketPayload {
        public static final Type<MapSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "map_overlay"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapSnapshot> CODEC = new StreamCodec<>() {
            @Override public MapSnapshot decode(RegistryFriendlyByteBuf buffer) {
                long sequence = buffer.readVarLong();
                ResourceLocation dimension = buffer.readResourceLocation();
                String json = buffer.readUtf(MapZoneModel.MAX_JSON_BYTES);
                boolean running = buffer.readBoolean();
                var next = buffer.readBoolean() ? new MapZoneModel.Circle(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()) : null;
                double flight = buffer.readDouble();
                return new MapSnapshot(sequence, dimension, json, running, next, flight,
                        com.example.examplemod.zones.match.MatchConfigPayloads.readStatus(buffer));
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, MapSnapshot packet) {
                buffer.writeVarLong(packet.sequence); buffer.writeResourceLocation(packet.dimension);
                buffer.writeUtf(packet.json, MapZoneModel.MAX_JSON_BYTES);
                buffer.writeBoolean(packet.running);
                buffer.writeBoolean(packet.nextCircle != null);
                if (packet.nextCircle != null) {
                    buffer.writeDouble(packet.nextCircle.centerX()); buffer.writeDouble(packet.nextCircle.centerZ());
                    buffer.writeDouble(packet.nextCircle.radius());
                }
                buffer.writeDouble(packet.flightProgress);
                com.example.examplemod.zones.match.MatchConfigPayloads.writeStatus(buffer, packet.matchStatus);
            }
        };
        public MapSnapshot {
            Objects.requireNonNull(dimension); Objects.requireNonNull(json);
            if (!Double.isFinite(flightProgress) || flightProgress < -1 || flightProgress > 1 || !running && flightProgress != -1)
                throw new IllegalArgumentException("Invalid flight progress");
            if (sequence < 0 || json.length() > MapZoneModel.MAX_JSON_BYTES)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.invalid_read_only_map_overlay_snapshot"));
            var map = json.isEmpty() ? null : MapZoneModel.parse(json);
            if (map != null && !map.dimension().equals(dimension))
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.map_overlay_dimension_does_not_match_the_viewer"));
            if (running && json.isEmpty()) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.a_running_match_needs_a_map_snapshot"));
            if (matchStatus != null && (!running || map == null || matchStatus.mapId() != map.id()
                    || !Objects.equals(matchStatus.currentCircle(), map.safeZone())
                    || !Objects.equals(nextCircle, com.example.examplemod.zones.match.MatchCirclePreview.target(matchStatus, map.id()))))
                throw new IllegalArgumentException("Map geometry and circle clock must be one coherent snapshot");
            if (nextCircle != null && (!running || map == null
                    || !map.contains(nextCircle.centerX(), nextCircle.centerZ())
                    || !Double.isFinite(nextCircle.radius()) || nextCircle.radius() <= 0
                    || nextCircle.radius() > MapZoneModel.MAX_RADIUS))
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.a_next_safe_circle_needs_valid_geometry_and_its_running_map"));
        }
        public MapSnapshot(long sequence, ResourceLocation dimension, String json, boolean running,
                MapZoneModel.Circle nextCircle, double flightProgress) {
            this(sequence, dimension, json, running, nextCircle, flightProgress, null);
        }
        public MapSnapshot(long sequence, ResourceLocation dimension, String json, boolean running, MapZoneModel.Circle nextCircle) {
            this(sequence, dimension, json, running, nextCircle, -1);
        }
        public MapSnapshot(long sequence, ResourceLocation dimension, String json, boolean running) {
            this(sequence, dimension, json, running, null);
        }
        public MapSnapshot(long sequence, ResourceLocation dimension, String json) { this(sequence, dimension, json, false); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Teammate(UUID id, String name, ResourceLocation dimension,
            double x, double y, double z, float health, float maxHealth, boolean alive, int slot, int color, boolean online) {
        public Teammate {
            Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(dimension);
            if (name.isBlank() || name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)
                    || !coordinate(x) || !coordinate(y) || !coordinate(z)
                    || !Float.isFinite(health) || !Float.isFinite(maxHealth) || health < 0 || maxHealth < 0
                    || health > 1_000_000 || maxHealth > 1_000_000 || slot < 1 || slot > MAX_TEAMMATES + 1)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.invalid_teammate_marker"));
        }
        public Teammate(UUID id, String name, ResourceLocation dimension, double x, double y, double z,
                float health, float maxHealth, boolean alive, int slot, int color) {
            this(id, name, dimension, x, y, z, health, maxHealth, alive, slot, color, true);
        }
        /** Offline roster rows have no trustworthy live coordinates and must never become map markers. */
        public boolean mapMarkerVisible(ResourceLocation displayedDimension) {
            return online && alive && dimension.equals(displayedDimension);
        }
    }

    public record TeamSnapshot(long sequence, ResourceLocation dimension, List<Teammate> teammates) implements CustomPacketPayload {
        public static final Type<TeamSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "team_overlay"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TeamSnapshot> CODEC = new StreamCodec<>() {
            @Override public TeamSnapshot decode(RegistryFriendlyByteBuf buffer) {
                long sequence = buffer.readVarLong();
                ResourceLocation dimension = buffer.readResourceLocation();
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_TEAMMATES) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.too_many_teammate_markers"));
                List<Teammate> teammates = new ArrayList<>();
                for (int i = 0; i < count; i++) teammates.add(new Teammate(buffer.readUUID(), buffer.readUtf(64),
                        buffer.readResourceLocation(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readFloat(), buffer.readFloat(), buffer.readBoolean(), buffer.readVarInt(), buffer.readInt(),
                        buffer.readBoolean()));
                return new TeamSnapshot(sequence, dimension, teammates);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, TeamSnapshot packet) {
                buffer.writeVarLong(packet.sequence); buffer.writeResourceLocation(packet.dimension);
                buffer.writeVarInt(packet.teammates.size());
                for (Teammate teammate : packet.teammates) {
                    buffer.writeUUID(teammate.id); buffer.writeUtf(teammate.name, 64);
                    buffer.writeResourceLocation(teammate.dimension);
                    buffer.writeDouble(teammate.x); buffer.writeDouble(teammate.y); buffer.writeDouble(teammate.z);
                    buffer.writeFloat(teammate.health); buffer.writeFloat(teammate.maxHealth);
                    buffer.writeBoolean(teammate.alive); buffer.writeVarInt(teammate.slot); buffer.writeInt(teammate.color);
                    buffer.writeBoolean(teammate.online);
                }
            }
        };
        public TeamSnapshot {
            Objects.requireNonNull(dimension);
            teammates = List.copyOf(teammates);
            if (sequence < 0 || teammates.size() > MAX_TEAMMATES) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.invalid_team_overlay_snapshot"));
            var ids = new HashSet<UUID>();
            // Roster status survives dimension changes; only the map renderer filters positions by dimension.
            for (Teammate teammate : teammates) if (!ids.add(teammate.id))
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.map_overlay_payloads.duplicate_or_cross_dimension_teammate_marker"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static boolean coordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 30_000_000; }
    public static void setClientHandlers(Consumer<MapSnapshot> maps, Consumer<TeamSnapshot> teams) {
        mapHandler = Objects.requireNonNull(maps); teamHandler = Objects.requireNonNull(teams);
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("9-circle-clock");
        registrar.playToClient(CatalogPart.TYPE, CatalogPart.CODEC,
                (packet, context) -> context.enqueueWork(() -> catalogHandler.accept(packet)));
        registrar.playToClient(MapSnapshot.TYPE, MapSnapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> mapHandler.accept(packet)));
        registrar.playToClient(TeamSnapshot.TYPE, TeamSnapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> teamHandler.accept(packet)));
    }
}
