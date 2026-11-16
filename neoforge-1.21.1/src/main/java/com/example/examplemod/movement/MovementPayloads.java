package com.example.examplemod.movement;

import com.example.examplemod.movement.MovementState.Mode;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class MovementPayloads {
    private MovementPayloads() { }

    public record Input(Mode mode, boolean active) implements CustomPacketPayload {
        public Input {
            Objects.requireNonNull(mode, "mode");
            if (!active) mode = Mode.STAND;
        }
        public static final Type<Input> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "ground_movement"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC = new StreamCodec<>() {
            @Override public Input decode(RegistryFriendlyByteBuf buffer) {
                int code = buffer.readUnsignedByte();
                return new Input(MovementModes.decode(code), code != 0);
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, Input value) {
                buffer.writeByte(MovementModes.encode(value.mode(), value.active()));
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1-ground-movement").playToServer(Input.TYPE, Input.CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        MovementService.input(player, packet.mode(), packet.active());
                }));
    }
}
