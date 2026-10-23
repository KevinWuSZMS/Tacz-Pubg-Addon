package com.example.examplemod.zones.match;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The server owns spectator eligibility and targets; clients request only previous/next teammate. */
public final class MatchSpectatorPayloads {
    private static final UUID NO_SESSION = new UUID(0, 0);
    private static final Map<MinecraftServer, Map<UUID, Snapshot>> SENT = new WeakHashMap<>();
    private static Consumer<Snapshot> clientHandler = ignored -> {};

    private MatchSpectatorPayloads() {}

    public record Snapshot(UUID sessionId, boolean active, boolean locked, int targetEntityId,
            String targetName, boolean teammatesOnly) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_spectator"));
        public static final Snapshot INACTIVE = new Snapshot(NO_SESSION, false, false, -1, "", false);
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            @Override public Snapshot decode(RegistryFriendlyByteBuf b) {
                return new Snapshot(b.readUUID(), b.readBoolean(), b.readBoolean(), b.readVarInt(),
                        b.readUtf(256), b.readBoolean());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Snapshot p) {
                b.writeUUID(p.sessionId()); b.writeBoolean(p.active()); b.writeBoolean(p.locked());
                b.writeVarInt(p.targetEntityId()); b.writeUtf(p.targetName(), 256); b.writeBoolean(p.teammatesOnly());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SwitchTarget(UUID sessionId, int direction) implements CustomPacketPayload {
        public static final Type<SwitchTarget> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_spectator_switch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SwitchTarget> CODEC = new StreamCodec<>() {
            @Override public SwitchTarget decode(RegistryFriendlyByteBuf b) {
                return new SwitchTarget(b.readUUID(), b.readByte());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, SwitchTarget p) {
                b.writeUUID(p.sessionId()); b.writeByte(p.direction());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void setClientHandler(Consumer<Snapshot> handler) { clientHandler = handler; }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1-match-spectator");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (packet, context) -> context.enqueueWork(() -> clientHandler.accept(packet)));
        registrar.playToServer(SwitchTarget.TYPE, SwitchTarget.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player)
                MatchSpectatorService.cycle(player, packet.sessionId(), packet.direction());
        }));
    }

    public static void registerGame(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post event) -> {
            MinecraftServer server = event.getServer();
            var sent = SENT.computeIfAbsent(server, ignored -> new HashMap<>());
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                var view = MatchSpectatorService.view(player);
                Snapshot next = view.active() ? new Snapshot(view.sessionId(), true, view.locked(),
                        view.targetEntityId(), view.targetName(), view.teammatesOnly()) : Snapshot.INACTIVE;
                Snapshot previous = sent.get(player.getUUID());
                if (!next.equals(previous) && (next.active() || previous != null)
                        || next.active() && server.getTickCount() % 20 == 0)
                    PacketDistributor.sendToPlayer(player, next);
                if (next.active()) sent.put(player.getUUID(), next);
                else sent.remove(player.getUUID());
            }
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player && SENT.containsKey(player.server))
                SENT.get(player.server).remove(player.getUUID());
        });
        bus.addListener((ServerStoppedEvent event) -> SENT.remove(event.getServer()));
    }
}
