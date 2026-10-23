package com.example.examplemod.backpack;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

public final class BackpackPayloads {
    private static Consumer<State> client = ignored -> {};
    private BackpackPayloads() {}
    public record State(UUID owner, int tier, int used, int capacity, boolean active) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "backpack_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, State s) {
                b.writeUUID(s.owner); b.writeVarInt(s.tier); b.writeVarInt(s.used); b.writeVarInt(s.capacity); b.writeBoolean(s.active);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Open() implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "backpack_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = new StreamCodec<>() {
            public Open decode(RegistryFriendlyByteBuf b) { return new Open(); }
            public void encode(RegistryFriendlyByteBuf b, Open s) { }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setClient(Consumer<State> handler) { client = handler; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1-backpack");
        registrar.playToClient(State.TYPE, State.CODEC, (p, c) -> c.enqueueWork(() -> client.accept(p)));
        registrar.playToServer(Open.TYPE, Open.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player && !(player.containerMenu instanceof BackpackMenu)) BackpackService.open(player);
        }));
    }
}
