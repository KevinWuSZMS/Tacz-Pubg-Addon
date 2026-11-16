package com.example.examplemod.lean;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class LeanPayloads {
    private LeanPayloads() { }
    public record Input(ResourceLocation dimension, int entityId, int sequence, int direction) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "lean_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = new StreamCodec<>() {
            @Override public Input decode(RegistryFriendlyByteBuf buffer) {
                return new Input(buffer.readResourceLocation(), buffer.readVarInt(), buffer.readVarInt(), buffer.readByte());
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Input value) {
                buffer.writeResourceLocation(value.dimension()); buffer.writeVarInt(value.entityId());
                buffer.writeVarInt(value.sequence()); buffer.writeByte(value.direction());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1-lean").playToServer(Input.TYPE, Input.CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player && player.level().dimension().location().equals(packet.dimension()))
                        LeanService.input(player, packet.entityId(), packet.sequence(), packet.direction());
                }));
    }
}
