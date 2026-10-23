package com.example.examplemod.modelscale;

import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = "tacz_pubg", bus = EventBusSubscriber.Bus.MOD)
public final class ModelScalePayloads {
    private static BiConsumer<State, Connection> client = (state, connection) -> {};
    private ModelScalePayloads() {}
    public static void setClient(BiConsumer<State, Connection> handler) { client = handler; }
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = ModelScalePayloads.type("model_scale_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Save(long expectedRevision, ModelScaleSettings settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = ModelScalePayloads.type("model_scale_save");
        public static final StreamCodec<RegistryFriendlyByteBuf, Save> CODEC = new StreamCodec<>() {
            @Override public Save decode(RegistryFriendlyByteBuf b) { return new Save(b.readVarLong(), ModelScaleSettings.parse(b.readUtf(32767))); }
            @Override public void encode(RegistryFriendlyByteBuf b, Save value) { b.writeVarLong(value.expectedRevision); b.writeUtf(value.settings.toJson(), 32767); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** status 0=sync, 1=saved, 2=revision conflict/unreadable data, 3=not an operator. */
    public record State(long revision, ModelScaleSettings settings, int status) implements CustomPacketPayload {
        public static final Type<State> TYPE = ModelScalePayloads.type("model_scale_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf b) { return new State(b.readVarLong(), ModelScaleSettings.parse(b.readUtf(32767)), b.readVarInt()); }
            @Override public void encode(RegistryFriendlyByteBuf b, State value) { b.writeVarLong(value.revision); b.writeUtf(value.settings.toJson(), 32767); b.writeVarInt(value.status); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", path));
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2-model-scales");
        registrar.playToClient(State.TYPE, State.CODEC,
                (value, context) -> context.enqueueWork(() -> client.accept(value, context.connection())));
        registrar.playToServer(Request.TYPE, Request.CODEC, (value, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) send(p, 0);
        }));
        registrar.playToServer(Save.TYPE, Save.CODEC, (value, context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer p)) return;
            if (!p.hasPermissions(2)) { send(p, 3); return; }
            var data = ModelScaleSavedData.get(p.server);
            if (!data.save(value.expectedRevision, value.settings)) { send(p, 2); return; }
            sync(p.server);
            send(p, 1);
        }));
    }
    public static void send(ServerPlayer p, int status) {
        var data = ModelScaleSavedData.get(p.server);
        PacketDistributor.sendToPlayer(p, new State(data.revision(), data.settings(), status));
    }
    public static void sync(net.minecraft.server.MinecraftServer server) {
        for (var p : server.getPlayerList().getPlayers()) send(p, 0);
    }
}
