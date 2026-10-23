package com.example.examplemod.visibility;

import com.example.examplemod.localization.Texts;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Small configuration channel and bounded read-only far-target snapshots. */
public final class VisibilityPayloads {
    public static final UUID PUSH = new UUID(0, 0);
    private static BiConsumer<State, Connection> stateHandler = (state, connection) -> {};
    private static BiConsumer<Targets, Connection> targetsHandler = (targets, connection) -> {};
    private VisibilityPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", path));
    }
    public record State(UUID request, long revision, VisibilityPolicy.Settings settings, boolean success, String message)
            implements CustomPacketPayload {
        public static final Type<State> TYPE = VisibilityPayloads.type("visibility_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf buffer) {
                return new State(buffer.readUUID(), buffer.readVarLong(), new VisibilityPolicy.Settings(buffer.readVarInt(), buffer.readVarInt()),
                        buffer.readBoolean(), buffer.readUtf(Texts.MAX_MESSAGE_LENGTH));
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, State state) {
                buffer.writeUUID(state.request); buffer.writeVarLong(state.revision);
                buffer.writeVarInt(state.settings.players()); buffer.writeVarInt(state.settings.targets());
                buffer.writeBoolean(state.success); buffer.writeUtf(state.message, Texts.MAX_MESSAGE_LENGTH);
            }
        };
        public State {
            Objects.requireNonNull(request); Objects.requireNonNull(settings); Objects.requireNonNull(message);
            if (revision < 0 || message.length() > Texts.MAX_MESSAGE_LENGTH) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.visibility_payloads.invalid_visibility_state"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Request(UUID request, boolean save, long revision, int players, int targets) implements CustomPacketPayload {
        public static final Type<Request> TYPE = VisibilityPayloads.type("visibility_edit");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            @Override public Request decode(RegistryFriendlyByteBuf buffer) {
                return new Request(buffer.readUUID(), buffer.readBoolean(), buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt());
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Request request) {
                buffer.writeUUID(request.request); buffer.writeBoolean(request.save); buffer.writeVarLong(request.revision);
                buffer.writeVarInt(request.players); buffer.writeVarInt(request.targets);
            }
        };
        public Request { Objects.requireNonNull(request); if (revision < -1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.visibility_payloads.invalid_visibility_revision")); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Target(BlockPos pos, int stateId, int packedLight, byte[] nbt) {
        public Target {
            pos = pos.immutable(); nbt = nbt.clone();
            if (stateId < 0 || nbt.length > VisibilityPolicy.MAX_TARGET_NBT_BYTES) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.visibility_payloads.invalid_target_snapshot"));
        }
        @Override public byte[] nbt() { return nbt.clone(); }
    }
    public record Targets(ResourceLocation dimension, long sequence, List<Target> targets, int omitted) implements CustomPacketPayload {
        public static final Type<Targets> TYPE = VisibilityPayloads.type("visibility_targets");
        public static final StreamCodec<RegistryFriendlyByteBuf, Targets> CODEC = new StreamCodec<>() {
            @Override public Targets decode(RegistryFriendlyByteBuf buffer) {
                ResourceLocation dimension = buffer.readResourceLocation(); long sequence = buffer.readVarLong();
                int omitted = buffer.readVarInt(), count = buffer.readVarInt();
                if (count < 0 || count > VisibilityPolicy.MAX_TARGETS) throw new DecoderException(Texts.tr("error.tacz_pubg.visibility_payloads.too_many_target_snapshots"));
                List<Target> targets = new ArrayList<>(count);
                for (int i = 0; i < count; i++) targets.add(new Target(buffer.readBlockPos(), buffer.readVarInt(), buffer.readInt(),
                        buffer.readByteArray(VisibilityPolicy.MAX_TARGET_NBT_BYTES)));
                return new Targets(dimension, sequence, targets, omitted);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Targets packet) {
                buffer.writeResourceLocation(packet.dimension); buffer.writeVarLong(packet.sequence);
                buffer.writeVarInt(packet.omitted); buffer.writeVarInt(packet.targets.size());
                for (Target target : packet.targets) {
                    buffer.writeBlockPos(target.pos); buffer.writeVarInt(target.stateId); buffer.writeInt(target.packedLight); buffer.writeByteArray(target.nbt);
                }
            }
        };
        public Targets {
            Objects.requireNonNull(dimension); targets = List.copyOf(targets);
            if (sequence < 0 || omitted < 0 || targets.size() > VisibilityPolicy.MAX_TARGETS)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.visibility_payloads.invalid_target_snapshot_batch"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setClientHandlers(BiConsumer<State, Connection> states, BiConsumer<Targets, Connection> targets) {
        stateHandler = Objects.requireNonNull(states); targetsHandler = Objects.requireNonNull(targets);
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2-i18n");
        registrar.playToClient(State.TYPE, State.CODEC,
                (packet, context) -> context.enqueueWork(() -> stateHandler.accept(packet, context.connection())));
        registrar.playToClient(Targets.TYPE, Targets.CODEC,
                (packet, context) -> context.enqueueWork(() -> targetsHandler.accept(packet, context.connection())));
        registrar.playToServer(Request.TYPE, Request.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var store = VisibilitySavedData.get(player.server);
            if (!player.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(player, state(store, packet.request, false, Texts.tr("message.tacz_pubg.visibility_payloads.permission_level_required_change_distances"))); return;
            }
            try {
                if (packet.save) store.update(packet.revision, new VisibilityPolicy.Settings(packet.players, packet.targets));
                PacketDistributor.sendToPlayer(player, state(store, packet.request, store.loadError().isEmpty(),
                        store.loadError().isEmpty() ? packet.save ? Texts.tr("message.tacz_pubg.visibility_payloads.distances_saved_applied_all_players") : Texts.tr("message.tacz_pubg.visibility_payloads.saved_per_world_visibility_distance_loaded_targets") : store.loadError()));
                if (packet.save) VisibilityService.syncSettings(player.server);
            } catch (RuntimeException error) {
                PacketDistributor.sendToPlayer(player, state(store, packet.request, false, String.valueOf(error.getMessage())));
            }
        }));
    }
    public static State state(VisibilitySavedData store, UUID request, boolean success, String message) {
        return new State(request, store.revision(), store.settings(), success, Texts.limit(message, Texts.MAX_MESSAGE_LENGTH));
    }
}
