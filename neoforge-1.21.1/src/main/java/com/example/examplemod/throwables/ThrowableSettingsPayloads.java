package com.example.examplemod.throwables;

import com.example.examplemod.localization.Texts;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Small authoritative overrides; full read-only catalog is sent only to the editor. */
public final class ThrowableSettingsPayloads {
    public static final UUID PUSH = new UUID(0, 0);
    private static BiConsumer<State, Connection> handler = (state, connection) -> {};
    private ThrowableSettingsPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", path));
    }
    public static void setHandler(BiConsumer<State, Connection> next) { handler = next; }
    public record Request(UUID request, boolean save, long revision, String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE = ThrowableSettingsPayloads.type("throwable_settings_edit");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readUUID(), b.readBoolean(), b.readVarLong(), b.readUtf(ThrowableSettingsModel.MAX_BYTES)); }
            public void encode(RegistryFriendlyByteBuf b, Request p) { b.writeUUID(p.request); b.writeBoolean(p.save); b.writeVarLong(p.revision); b.writeUtf(p.json, ThrowableSettingsModel.MAX_BYTES); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record State(UUID request, long revision, String json, String catalog, boolean success, String message) implements CustomPacketPayload {
        public static final Type<State> TYPE = ThrowableSettingsPayloads.type("throwable_settings_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readUUID(), b.readVarLong(), b.readUtf(ThrowableSettingsModel.MAX_BYTES), b.readUtf(262_144), b.readBoolean(), b.readUtf(Texts.MAX_MESSAGE_LENGTH)); }
            public void encode(RegistryFriendlyByteBuf b, State p) { b.writeUUID(p.request); b.writeVarLong(p.revision); b.writeUtf(p.json, ThrowableSettingsModel.MAX_BYTES); b.writeUtf(p.catalog, 262_144); b.writeBoolean(p.success); b.writeUtf(p.message, Texts.MAX_MESSAGE_LENGTH); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var channel = event.registrar("1");
        channel.playToClient(State.TYPE, State.CODEC, (p, ctx) -> ctx.enqueueWork(() -> handler.accept(p, ctx.connection())));
        channel.playToServer(Request.TYPE, Request.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
            var store = ThrowableSettingsSavedData.get(player.server);
            try {
                if (p.save) store.update(p.revision, ThrowableSettingsModel.parse(p.json));
                send(player, p.request, store.error().isEmpty(), store.error(), true);
                if (p.save) for (var other : player.server.getPlayerList().getPlayers()) push(other);
            } catch (RuntimeException failure) { send(player, p.request, false, String.valueOf(failure.getMessage()), true); }
        }));
    }
    public static void push(ServerPlayer player) { send(player, PUSH, true, "", false); }
    private static void send(ServerPlayer player, UUID request, boolean success, String message, boolean catalog) {
        var store = ThrowableSettingsSavedData.get(player.server);
        PacketDistributor.sendToPlayer(player, new State(request, store.revision(), store.settings().toString(),
                catalog ? ThrowableSettingsService.catalog().toString() : "{}", success, Texts.limit(message, Texts.MAX_MESSAGE_LENGTH)));
    }
}
