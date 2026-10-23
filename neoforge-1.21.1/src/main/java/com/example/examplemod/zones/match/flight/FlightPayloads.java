package com.example.examplemod.zones.match.flight;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class FlightPayloads {
    private FlightPayloads() {}
    private static Consumer<State> client = ignored -> {};
    public record State(UUID session, int stage, int jumpWait, boolean canDeploy, int autoDeployHeight,
            double launchHeight) implements CustomPacketPayload {
        public static final State NONE = new State(new UUID(0, 0), 0, 0, false, 0, 0);
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "flight_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) {
                return new State(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readDouble());
            }
            public void encode(RegistryFriendlyByteBuf b, State p) {
                b.writeUUID(p.session); b.writeVarInt(p.stage); b.writeVarInt(p.jumpWait);
                b.writeBoolean(p.canDeploy); b.writeVarInt(p.autoDeployHeight); b.writeDouble(p.launchHeight);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Jump(UUID session) implements CustomPacketPayload {
        public static final Type<Jump> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "flight_jump"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Jump> CODEC = new StreamCodec<>() {
            public Jump decode(RegistryFriendlyByteBuf b) { return new Jump(b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, Jump p) { b.writeUUID(p.session); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Deploy(UUID session) implements CustomPacketPayload {
        public static final Type<Deploy> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "flight_deploy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Deploy> CODEC = new StreamCodec<>() {
            public Deploy decode(RegistryFriendlyByteBuf b) { return new Deploy(b.readUUID()); }
            public void encode(RegistryFriendlyByteBuf b, Deploy p) { b.writeUUID(p.session); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setClientHandler(Consumer<State> handler) { client = handler; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("2-flight");
        r.playToClient(State.TYPE, State.CODEC, (packet, context) -> context.enqueueWork(() -> client.accept(packet)));
        r.playToServer(Jump.TYPE, Jump.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) MatchFlightService.requestJump(p, packet.session());
        }));
        r.playToServer(Deploy.TYPE, Deploy.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) MatchFlightService.requestDeploy(p, packet.session());
        }));
    }
}
