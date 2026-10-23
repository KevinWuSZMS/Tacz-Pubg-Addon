package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Read-only confirmation; a client cannot turn on the experiment or edit server settings. */
public final class GunExperimentPayloads {
    private static BiConsumer<State, Connection> clientHandler = (ignored, connection) -> {};
    private GunExperimentPayloads() {}

    public record State(long revision, boolean enabled, boolean magazineEnabled) implements CustomPacketPayload {
        /** Existing balance-only callers cannot implicitly enable magazine behavior. */
        public State(long revision, boolean enabled) { this(revision, enabled, false); }
        public static final Type<State> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "gun_experiment_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf buffer) {
                return new State(buffer.readVarLong(), buffer.readBoolean(), buffer.readBoolean());
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, State state) {
                buffer.writeVarLong(state.revision); buffer.writeBoolean(state.enabled);
                buffer.writeBoolean(state.magazineEnabled);
            }
        };
        public State {
            if (revision < 0) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.gun_experiment_payloads.negative_gun_experiment_revision"));
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void setClientHandler(BiConsumer<State, Connection> handler) { clientHandler = Objects.requireNonNull(handler); }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Required on both ends: also guards the versioned TaCZ projectile spawn-data extension.
        event.registrar("2").playToClient(State.TYPE, State.CODEC,
                (state, context) -> context.enqueueWork(() -> clientHandler.accept(state, context.connection())));
    }
}
