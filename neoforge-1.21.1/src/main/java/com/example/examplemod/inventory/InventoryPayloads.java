package com.example.examplemod.inventory;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class InventoryPayloads {
    private static final Gson JSON = new Gson();
    private static BiConsumer<State, Connection> client = (state, connection) -> {};
    private InventoryPayloads() {}
    public record State(long revision, boolean active, InventorySettings settings, int equipmentLimit) implements CustomPacketPayload {
        public State(long revision, boolean active, InventorySettings settings) { this(revision, active, settings, 1); }
        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_inventory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf b) {
                return new State(b.readVarLong(), b.readBoolean(), InventorySettings.parse(JsonParser.parseString(b.readUtf(1_048_576))), b.readVarInt());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, State value) {
                b.writeVarLong(value.revision); b.writeBoolean(value.active); b.writeUtf(JSON.toJson(value.settings), 1_048_576);
                b.writeVarInt(value.equipmentLimit);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setClient(BiConsumer<State, Connection> handler) { client = handler; }
    public static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("2-equipment-limit").playToClient(State.TYPE, State.CODEC,
                (state, context) -> context.enqueueWork(() -> client.accept(state, context.connection())));
    }
}
