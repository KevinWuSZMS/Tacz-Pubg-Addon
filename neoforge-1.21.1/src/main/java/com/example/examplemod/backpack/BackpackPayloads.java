package com.example.examplemod.backpack;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

public final class BackpackPayloads {
    private static Consumer<State> client = ignored -> {};
    private BackpackPayloads() {}
    public record State(UUID owner, int tier, int used, int capacity, boolean active, ItemStack equipment) implements CustomPacketPayload {
        public State { equipment = equipment.copy(); }
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "backpack_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b)); }
            public void encode(RegistryFriendlyByteBuf b, State s) {
                b.writeUUID(s.owner); b.writeVarInt(s.tier); b.writeVarInt(s.used); b.writeVarInt(s.capacity); b.writeBoolean(s.active);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(b, s.equipment);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setClient(Consumer<State> handler) { client = handler; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2-backpack");
        registrar.playToClient(State.TYPE, State.CODEC, (p, c) -> c.enqueueWork(() -> { BackpackService.acceptClient(p); client.accept(p); }));
    }
}
