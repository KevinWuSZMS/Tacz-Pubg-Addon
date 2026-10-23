package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import com.example.examplemod.lootconfig.LootTier;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** S2C author overlays and bounded C2S tool actions. Client packets cannot supply floor coordinates. */
public final class SpawnAreaPayloads {
    public static final int SYNC_CHUNK_RADIUS = 4;
    public static final int MAX_SYNC_FLOORS = SpawnAreaSavedData.MAX_FLOORS_PER_DIMENSION;
    private static volatile Consumer<Snapshot> clientHandler = ignored -> {};

    private SpawnAreaPayloads() {}

    /** Called by client setup without a common-side dependency on client classes. */
    public static void setClientHandler(Consumer<Snapshot> handler) {
        clientHandler = Objects.requireNonNull(handler);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(Snapshot.TYPE, Snapshot.STREAM_CODEC,
                (snapshot, context) -> context.enqueueWork(() -> clientHandler.accept(snapshot)))
                .playToServer(ToolRequest.TYPE, ToolRequest.STREAM_CODEC,
                        (request, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player)
                                SpawnAreaService.handleShortcut(player, request.action(), request.previewDimension());
                        }));
    }

    public enum ToolAction { CLEAR, SAVE, ERASE, MAP_PREVIEW, END_MAP_PREVIEW }

    public record ToolRequest(ToolAction action, @Nullable ResourceLocation previewDimension) implements CustomPacketPayload {
        public ToolRequest(ToolAction action) { this(action, null); }
        public static final Type<ToolRequest> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "spawn_area_tool_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToolRequest> STREAM_CODEC = new StreamCodec<>() {
            @Override public ToolRequest decode(RegistryFriendlyByteBuf buffer) {
                int ordinal = buffer.readUnsignedByte();
                if (ordinal >= ToolAction.values().length)
                    throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_area_payloads.invalid_spawn_area_tool_action") + ordinal);
                return new ToolRequest(ToolAction.values()[ordinal], buffer.readBoolean() ? buffer.readResourceLocation() : null);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, ToolRequest value) {
                buffer.writeByte(value.action().ordinal());
                buffer.writeBoolean(value.previewDimension() != null);
                if (value.previewDimension() != null) buffer.writeResourceLocation(value.previewDimension());
            }
        };
        public ToolRequest { Objects.requireNonNull(action); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** visible is the personal preference; holding the editing stick also enables viewing. */
    public record Snapshot(ResourceLocation dimension, boolean visible, long revision,
            List<BlockPos> floors, @Nullable BlockPos pos1, @Nullable BlockPos pos2,
            boolean truncated, Map<Long, LootTier> floorTiers, LootTier selectionTier, Map<String, Integer> lootPalette,
            int lootOverlapBlackLayers, boolean globalLootPalette) implements CustomPacketPayload {
        public Snapshot(ResourceLocation dimension, boolean visible, long revision, List<BlockPos> floors,
                @Nullable BlockPos pos1, @Nullable BlockPos pos2, boolean truncated) {
            this(dimension, visible, revision, floors, pos1, pos2, truncated, Map.of(), LootTier.HIGH, com.example.examplemod.zones.MapVisualStyle.defaultLootTierRgb(), 5, false);
        }
        public static final Type<Snapshot> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "spawn_area_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Snapshot decode(RegistryFriendlyByteBuf buffer) {
                ResourceLocation dimension = buffer.readResourceLocation();
                boolean visible = buffer.readBoolean();
                long revision = buffer.readVarLong();
                int size = buffer.readVarInt();
                if (size < 0 || size > MAX_SYNC_FLOORS || size > buffer.readableBytes() / Long.BYTES) {
                    throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_area_payloads.invalid_spawn_area_snapshot_size") + size);
                }
                List<BlockPos> floors = new ArrayList<>(size);
                Map<Long, LootTier> tiers = new HashMap<>();
                for (int i = 0; i < size; i++) {
                    long packed = buffer.readLong(); floors.add(BlockPos.of(packed));
                    int ordinal = buffer.readUnsignedByte();
                    if (ordinal >= LootTier.values().length) throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_area_payloads.invalid_loot_tier"));
                    tiers.put(packed, LootTier.values()[ordinal]);
                }
                BlockPos first = buffer.readBoolean() ? buffer.readBlockPos() : null;
                BlockPos second = buffer.readBoolean() ? buffer.readBlockPos() : null;
                boolean truncated = buffer.readBoolean();
                int ordinal = buffer.readUnsignedByte();
                if (ordinal >= LootTier.values().length) throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_area_payloads.invalid_selection_tier"));
                Map<String, Integer> palette = new HashMap<>();
                for (LootTier tier : LootTier.values()) {
                    int rgb = buffer.readInt();
                    if (rgb < 0 || rgb > 0xffffff) throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_area_payloads.invalid_loot_colour"));
                    palette.put(tier.id(), rgb);
                }
                int layers = buffer.readVarInt();
                if (layers < 2 || layers > 256) throw new DecoderException(Texts.tr("error.tacz_pubg.spawn_area_payloads.invalid_loot_overlap_layers"));
                return new Snapshot(dimension, visible, revision, floors, first, second, truncated, tiers, LootTier.values()[ordinal], palette, layers, buffer.readBoolean());
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, Snapshot snapshot) {
                buffer.writeResourceLocation(snapshot.dimension());
                buffer.writeBoolean(snapshot.visible());
                buffer.writeVarLong(snapshot.revision());
                buffer.writeVarInt(snapshot.floors().size());
                for (BlockPos pos : snapshot.floors()) {
                    buffer.writeLong(pos.asLong());
                    buffer.writeByte(snapshot.floorTiers().getOrDefault(pos.asLong(), LootTier.HIGH).ordinal());
                }
                buffer.writeBoolean(snapshot.pos1() != null);
                if (snapshot.pos1() != null) {
                    buffer.writeBlockPos(snapshot.pos1());
                }
                buffer.writeBoolean(snapshot.pos2() != null);
                if (snapshot.pos2() != null) {
                    buffer.writeBlockPos(snapshot.pos2());
                }
                buffer.writeBoolean(snapshot.truncated());
                buffer.writeByte(snapshot.selectionTier().ordinal());
                for (LootTier tier : LootTier.values()) buffer.writeInt(snapshot.lootPalette().get(tier.id()));
                buffer.writeVarInt(snapshot.lootOverlapBlackLayers()); buffer.writeBoolean(snapshot.globalLootPalette());
            }
        };

        public Snapshot {
            Objects.requireNonNull(dimension);
            floors = List.copyOf(floors);
            Map<Long, LootTier> complete = new HashMap<>();
            for (BlockPos floor : floors) complete.put(floor.asLong(), floorTiers.getOrDefault(floor.asLong(), LootTier.HIGH));
            floorTiers = Map.copyOf(complete);
            Objects.requireNonNull(selectionTier);
            lootPalette = Map.copyOf(lootPalette);
            if (floors.size() > MAX_SYNC_FLOORS) {
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_payloads.too_many_floors_in_one_spawn_area_snapshot"));
            }
            pos1 = pos1 == null ? null : pos1.immutable();
            pos2 = pos2 == null ? null : pos2.immutable();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
