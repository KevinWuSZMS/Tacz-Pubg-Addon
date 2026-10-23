package com.example.examplemod.originalicons;

import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class OriginalIconPayloads {
    private static BiConsumer<State, Connection> handler = (state, connection) -> {};
    private OriginalIconPayloads() {}
    public static void setClientHandler(BiConsumer<State, Connection> value) { handler = value; }
    public record State(long revision, boolean enabled, boolean lowPolyModels) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "original_icons"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf buffer) {
                return new State(buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean());
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, State value) {
                buffer.writeVarLong(value.revision()); buffer.writeBoolean(value.enabled());
                buffer.writeBoolean(value.lowPolyModels());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(State.TYPE, State.CODEC,
                (value, context) -> context.enqueueWork(() -> handler.accept(value, context.connection())));
    }
}
