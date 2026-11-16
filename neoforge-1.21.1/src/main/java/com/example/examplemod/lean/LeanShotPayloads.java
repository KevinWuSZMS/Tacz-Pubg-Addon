package com.example.examplemod.lean;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class LeanShotPayloads {
    private LeanShotPayloads() {}

    /** Native shot timestamp + actual burst index; clients never supply a world-space origin. */
    public record Pose(long timestamp, int round, int sequence, double inputAgeSeconds, float pitch, float yaw)
            implements CustomPacketPayload {
        public static final Type<Pose> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "lean_shot_pose"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pose> CODEC = new StreamCodec<>() {
            @Override public Pose decode(RegistryFriendlyByteBuf buffer) {
                return new Pose(buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readDouble(), buffer.readFloat(), buffer.readFloat());
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Pose value) {
                buffer.writeVarLong(value.timestamp()); buffer.writeVarInt(value.round()); buffer.writeVarInt(value.sequence());
                buffer.writeDouble(value.inputAgeSeconds()); buffer.writeFloat(value.pitch()); buffer.writeFloat(value.yaw());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Pose.TYPE, Pose.CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) LeanShots.accept(player, packet);
                }));
    }
}
