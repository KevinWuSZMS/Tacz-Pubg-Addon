package com.example.examplemod.pubgsettings;

import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class PubgSettingsPayloads {
    private static BiConsumer<State,Connection> client = (state,connection) -> {};
    private PubgSettingsPayloads() {}
    public static void setClient(BiConsumer<State,Connection> handler) { client = handler; }
    public record Request(long requestId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = id("battle_settings_request");
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC = StreamCodec.of(
                (b,p) -> b.writeVarLong(p.requestId),b -> new Request(b.readVarLong()));
        public Request { if (requestId <= 0) throw new IllegalArgumentException("Explicit settings requests need a positive identity"); }
        public Request() { this(1); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Save(long requestId,long expectedRevision,PubgSettings settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = id("battle_settings_save");
        public static final StreamCodec<RegistryFriendlyByteBuf,Save> CODEC = new StreamCodec<>() {
            public Save decode(RegistryFriendlyByteBuf b) { return new Save(b.readVarLong(),b.readVarLong(),PubgSettings.parse(b.readUtf(PubgSettings.MAX_JSON))); }
            public void encode(RegistryFriendlyByteBuf b,Save p) { b.writeVarLong(p.requestId); b.writeVarLong(p.expectedRevision); b.writeUtf(p.settings.toJson(),PubgSettings.MAX_JSON); }
        };
        public Save { if (requestId <= 0 || expectedRevision < 0 || settings == null) throw new IllegalArgumentException("Invalid settings update"); }
        public Save(long expectedRevision,PubgSettings settings) { this(1,expectedRevision,settings); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Status 0 sync, 1 accepted, 2 stale/unreadable, 3 denied. */
    public record State(long requestId,long revision,PubgSettings settings,int status) implements CustomPacketPayload {
        public static final Type<State> TYPE = id("battle_settings_state");
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC = new StreamCodec<>() {
            public State decode(RegistryFriendlyByteBuf b) { return new State(b.readVarLong(),b.readVarLong(),PubgSettings.parse(b.readUtf(PubgSettings.MAX_JSON)),b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b,State p) { b.writeVarLong(p.requestId); b.writeVarLong(p.revision); b.writeUtf(p.settings.toJson(),PubgSettings.MAX_JSON); b.writeVarInt(p.status); }
        };
        public State { if (requestId < 0 || revision < 0 || settings == null || status < 0 || status > 3
                || status != 0 && requestId == 0 && status != 2) throw new IllegalArgumentException("Invalid settings response"); }
        public State(long revision,PubgSettings settings,int status) { this(0,revision,settings,status); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg",path));
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("4-battle-settings");
        registrar.playToClient(State.TYPE,State.CODEC,(packet,context) -> context.enqueueWork(() -> client.accept(packet,context.connection())));
        registrar.playToServer(Request.TYPE,Request.CODEC,(packet,context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) send(p,0,packet.requestId);
        }));
        registrar.playToServer(Save.TYPE,Save.CODEC,(packet,context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer p)) return;
            if (!p.hasPermissions(2)) { send(p,3,packet.requestId); return; }
            var data = PubgSettingsSavedData.get(p.server);
            if (!data.update(packet.expectedRevision,packet.settings)) { send(p,2,packet.requestId); return; }
            for (var player : p.server.getPlayerList().getPlayers()) send(player,player == p ? 1 : 0,player == p ? packet.requestId : 0);
        }));
    }
    public static void send(ServerPlayer player,int status) {
        send(player,status,0);
    }
    public static void send(ServerPlayer player,int status,long requestId) {
        var data = PubgSettingsSavedData.get(player.server);
        PacketDistributor.sendToPlayer(player,new State(requestId,data.revision(),data.settings(),data.readable()?status:2));
    }
}
