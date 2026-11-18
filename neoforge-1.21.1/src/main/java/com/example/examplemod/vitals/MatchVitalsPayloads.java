package com.example.examplemod.vitals;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Personal authoritative state: watching a map never opts a bystander into match vitals. */
public final class MatchVitalsPayloads {
    public static final UUID NO_SESSION = new UUID(0, 0);
    /** Ordinary-world boost has a distinct identity, without opting the player into match rules. */
    public static final UUID ORDINARY_SESSION = new UUID(0, 1);
    private static Consumer<Snapshot> clientHandler = ignored -> {};
    private static Consumer<MedicineStarted> medicineHandler = ignored -> {};
    private static Consumer<MedicineCompleted> medicineCompletedHandler = ignored -> {};
    private static Consumer<RecoveryState> recoveryHandler = ignored -> {};
    private MatchVitalsPayloads() {}

    public record StartMedicine(int slot, int request) implements CustomPacketPayload {
        public static final Type<StartMedicine> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "start_held_medicine"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StartMedicine> CODEC = new StreamCodec<>() {
            @Override public StartMedicine decode(RegistryFriendlyByteBuf b) { return new StartMedicine(b.readVarInt(), b.readVarInt()); }
            @Override public void encode(RegistryFriendlyByteBuf b, StartMedicine p) { b.writeVarInt(p.slot()); b.writeVarInt(p.request()); }
        };
        public StartMedicine { if (slot < 0 || slot > 8 || request <= 0) throw new IllegalArgumentException("Invalid medicine request"); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record MedicineStarted(int slot, int request, int duration) implements CustomPacketPayload {
        public static final Type<MedicineStarted> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "held_medicine_started"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MedicineStarted> CODEC = new StreamCodec<>() {
            @Override public MedicineStarted decode(RegistryFriendlyByteBuf b) { return new MedicineStarted(b.readVarInt(), b.readVarInt(), b.readVarInt()); }
            @Override public void encode(RegistryFriendlyByteBuf b, MedicineStarted p) { b.writeVarInt(p.slot()); b.writeVarInt(p.request()); b.writeVarInt(p.duration()); }
        };
        public MedicineStarted { if (slot < 0 || slot > 8 || request <= 0 || duration < 0 || duration > 1200) throw new IllegalArgumentException("Invalid medicine response"); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** A completion is emitted only after the server has granted the treatment and consumed the item. */
    public record MedicineCompleted(UUID session, PubgMedicineModel.Kind kind) implements CustomPacketPayload {
        public static final Type<MedicineCompleted> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "medicine_completed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MedicineCompleted> CODEC = new StreamCodec<>() {
            @Override public MedicineCompleted decode(RegistryFriendlyByteBuf b) { return new MedicineCompleted(b.readUUID(), b.readEnum(PubgMedicineModel.Kind.class)); }
            @Override public void encode(RegistryFriendlyByteBuf b, MedicineCompleted p) { b.writeUUID(p.session()); b.writeEnum(p.kind()); }
        };
        public MedicineCompleted { Objects.requireNonNull(session); Objects.requireNonNull(kind); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record CancelMedicine(int request) implements CustomPacketPayload {
        public static final Type<CancelMedicine> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "cancel_held_medicine"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CancelMedicine> CODEC = new StreamCodec<>() {
            @Override public CancelMedicine decode(RegistryFriendlyByteBuf b) { return new CancelMedicine(b.readVarInt()); }
            @Override public void encode(RegistryFriendlyByteBuf b, CancelMedicine p) { b.writeVarInt(p.request()); }
        };
        public CancelMedicine { if (request <= 0) throw new IllegalArgumentException("Invalid medicine cancellation"); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record RecoveryState(UUID session, double remaining, double next, double ceiling, int remainingTicks) implements CustomPacketPayload {
        public static final Type<RecoveryState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "medical_recovery"));
        public static final RecoveryState INACTIVE = new RecoveryState(NO_SESSION, 0, 0, 0, 0);
        public static final StreamCodec<RegistryFriendlyByteBuf, RecoveryState> CODEC = new StreamCodec<>() {
            @Override public RecoveryState decode(RegistryFriendlyByteBuf b) { return new RecoveryState(b.readUUID(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt()); }
            @Override public void encode(RegistryFriendlyByteBuf b, RecoveryState p) { b.writeUUID(p.session()); b.writeDouble(p.remaining()); b.writeDouble(p.next()); b.writeDouble(p.ceiling()); b.writeVarInt(p.remainingTicks()); }
        };
        public RecoveryState {
            Objects.requireNonNull(session);
            if (!Double.isFinite(remaining) || remaining < 0 || !Double.isFinite(next) || next < 0 || next > remaining
                    || !Double.isFinite(ceiling) || ceiling < 0 || ceiling > 1 || remainingTicks < 0 || remainingTicks > 100_000)
                throw new IllegalArgumentException("Invalid medical recovery state");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Snapshot(UUID sessionId, boolean active, boolean paused, float boost,
            float health, float maxHealth, float absorption) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_vitals"));
        public static final Snapshot INACTIVE = new Snapshot(NO_SESSION, false, false, 0, 0, 20, 0);
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            @Override public Snapshot decode(RegistryFriendlyByteBuf b) {
                return new Snapshot(b.readUUID(), b.readBoolean(), b.readBoolean(), b.readFloat(),
                        b.readFloat(), b.readFloat(), b.readFloat());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Snapshot p) {
                b.writeUUID(p.sessionId()); b.writeBoolean(p.active()); b.writeBoolean(p.paused());
                b.writeFloat(p.boost()); b.writeFloat(p.health()); b.writeFloat(p.maxHealth()); b.writeFloat(p.absorption());
            }
        };
        public Snapshot {
            Objects.requireNonNull(sessionId);
            if (active == sessionId.equals(NO_SESSION) || !active && (paused || boost != 0)
                    || !Float.isFinite(boost) || boost < 0 || boost > 100
                    || !Float.isFinite(health) || health < 0 || !Float.isFinite(maxHealth) || maxHealth <= 0
                    || !Float.isFinite(absorption) || absorption < 0)
                throw new IllegalArgumentException("Invalid personal match vitals");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
        public boolean inMatch() { return active && !sessionId.equals(ORDINARY_SESSION); }
    }
    public static void setClientHandler(Consumer<Snapshot> handler) { clientHandler = Objects.requireNonNull(handler); }
    public static void setMedicineClientHandler(Consumer<MedicineStarted> handler) { medicineHandler = Objects.requireNonNull(handler); }
    public static void setMedicineCompletedClientHandler(Consumer<MedicineCompleted> handler) { medicineCompletedHandler = Objects.requireNonNull(handler); }
    public static void setRecoveryClientHandler(Consumer<RecoveryState> handler) { recoveryHandler = Objects.requireNonNull(handler); }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("4-match-vitals");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> clientHandler.accept(packet)));
        registrar.playToServer(StartMedicine.TYPE, StartMedicine.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) PubgMedicineService.startHeld(player, packet.slot(), packet.request());
        }));
        registrar.playToClient(MedicineStarted.TYPE, MedicineStarted.CODEC, (packet, context) -> context.enqueueWork(() -> medicineHandler.accept(packet)));
        registrar.playToClient(MedicineCompleted.TYPE, MedicineCompleted.CODEC, (packet, context) -> context.enqueueWork(() -> medicineCompletedHandler.accept(packet)));
        registrar.playToClient(RecoveryState.TYPE, RecoveryState.CODEC, (packet, context) -> context.enqueueWork(() -> recoveryHandler.accept(packet)));
        registrar.playToServer(CancelMedicine.TYPE, CancelMedicine.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) PubgMedicineService.cancelRequest(player, packet.request());
        }));
    }
}
