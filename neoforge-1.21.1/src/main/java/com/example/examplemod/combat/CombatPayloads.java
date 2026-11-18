package com.example.examplemod.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Semantic combat identities and authoritative counters; no client entity lookup is required. */
public final class CombatPayloads {
    public static final UUID NO_SESSION = new UUID(0, 0);
    public record Actor(UUID id, String name, int team, int color) {
        public static final Actor NONE = new Actor(NO_SESSION, "", 0, 0xff888888);
        public Actor { if (id == null || name == null || name.length() > 64 || team < 0 || team > 100) throw new IllegalArgumentException("Invalid combat actor"); }
    }
    public record Rescue(UUID target, String targetName, UUID rescuer, String rescuerName, int elapsedTicks, int durationTicks, boolean active) {
        public static final Rescue NONE = new Rescue(NO_SESSION, "", NO_SESSION, "", 0, 0, false);
        public Rescue { if (target == null || rescuer == null || targetName.length() > 64 || rescuerName.length() > 64 || elapsedTicks < 0 || durationTicks < elapsedTicks || durationTicks > 72000) throw new IllegalArgumentException("Invalid rescue progress"); }
    }
    public record DownedPlayer(UUID id, float health, float maxHealth, boolean downed) {
        public DownedPlayer { if (id == null || !Float.isFinite(health) || !Float.isFinite(maxHealth) || health < 0 || maxHealth < health || maxHealth > 10000) throw new IllegalArgumentException("Invalid downed player"); }
    }
    public record Snapshot(UUID session, long sequence, int kills, int assists, int knocks, boolean downed,
            float downedHealth, float downedMaxHealth, int downCount, Rescue rescue, List<DownedPlayer> players,
            int viewerTeam) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = CombatPayloads.type("combat_snapshot");
        public static final Snapshot EMPTY = new Snapshot(NO_SESSION, 0, 0, 0, 0, false, 0, 0, 0, Rescue.NONE, List.of(), 0);
        public Snapshot {
            players = List.copyOf(players);
            if (session == null || sequence < 0 || kills < 0 || assists < 0 || knocks < 0 || downCount < 0 || rescue == null || players.size() > 100 || viewerTeam < 0 || viewerTeam > 100
                    || !Float.isFinite(downedHealth) || !Float.isFinite(downedMaxHealth) || downedHealth < 0 || downedMaxHealth < downedHealth || downedMaxHealth > 10000) throw new IllegalArgumentException("Invalid combat snapshot");
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b) {
                UUID session = b.readUUID(); long sequence = b.readVarLong(); int kills = b.readVarInt(), assists = b.readVarInt(), knocks = b.readVarInt();
                boolean down = b.readBoolean(); float hp = b.readFloat(), max = b.readFloat(); int count = b.readVarInt(); Rescue rescue = readRescue(b);
                int size = b.readVarInt(); if (size < 0 || size > 100) throw new IllegalArgumentException("Too many combat identities");
                var players = new ArrayList<DownedPlayer>(size);
                for (int i = 0; i < size; i++) players.add(new DownedPlayer(b.readUUID(), b.readFloat(), b.readFloat(), b.readBoolean()));
                return new Snapshot(session, sequence, kills, assists, knocks, down, hp, max, count, rescue, players, b.readVarInt());
            }
            public void encode(RegistryFriendlyByteBuf b, Snapshot p) {
                b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeVarInt(p.kills); b.writeVarInt(p.assists); b.writeVarInt(p.knocks);
                b.writeBoolean(p.downed); b.writeFloat(p.downedHealth); b.writeFloat(p.downedMaxHealth); b.writeVarInt(p.downCount); writeRescue(b, p.rescue);
                b.writeVarInt(p.players.size()); for (var player : p.players) { b.writeUUID(player.id); b.writeFloat(player.health); b.writeFloat(player.maxHealth); b.writeBoolean(player.downed); }
                b.writeVarInt(p.viewerTeam);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Event(UUID session, long sequence, String type, Actor attacker, Actor victim, String weaponId,
            boolean headshot, String cause, int kills, int assists) {
        public boolean firearm() { return (cause.equals("gun") || cause.equals("gun_blast")) && !weaponId.isEmpty(); }
        public boolean unarmed() { return cause.equals("unarmed"); }
        public boolean throwable() { return cause.equals("throwable"); }
        public boolean bleedout() { return type.equals("elimination") && cause.equals("bleedout"); }
        public boolean friendlyFire() {
            return (type.equals("knock") || type.equals("elimination")) && attacker.team > 0
                    && attacker.team == victim.team && !attacker.id.equals(victim.id);
        }
        public Event { if (session == null || sequence < 0 || !List.of("knock", "elimination", "assist", "revive").contains(type) || attacker == null || victim == null || weaponId.length() > 256 || cause.length() > 64 || kills < 0 || assists < 0) throw new IllegalArgumentException("Invalid combat event"); }
        public static final StreamCodec<RegistryFriendlyByteBuf, Event> CODEC = new StreamCodec<>() {
            public Event decode(RegistryFriendlyByteBuf b) { return new Event(b.readUUID(), b.readVarLong(), b.readUtf(16), readActor(b), readActor(b), b.readUtf(256), b.readBoolean(), b.readUtf(64), b.readVarInt(), b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, Event p) { b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeUtf(p.type,16); writeActor(b,p.attacker); writeActor(b,p.victim); b.writeUtf(p.weaponId,256); b.writeBoolean(p.headshot); b.writeUtf(p.cause,64); b.writeVarInt(p.kills); b.writeVarInt(p.assists); }
        };
    }
    public record EventMessage(Event event) implements CustomPacketPayload {
        public static final Type<EventMessage> TYPE=CombatPayloads.type("combat_event");
        public static final StreamCodec<RegistryFriendlyByteBuf,EventMessage> CODEC=new StreamCodec<>() {
            public EventMessage decode(RegistryFriendlyByteBuf b){return new EventMessage(Event.CODEC.decode(b));}
            public void encode(RegistryFriendlyByteBuf b,EventMessage p){Event.CODEC.encode(b,p.event);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** 0 begins/toggles a rescue, 1 cancels; the server always resolves target eligibility again. */
    public record RescueAction(UUID target, boolean cancel) implements CustomPacketPayload {
        public static final Type<RescueAction> TYPE = CombatPayloads.type("rescue_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, RescueAction> CODEC = new StreamCodec<>() {
            public RescueAction decode(RegistryFriendlyByteBuf b) { return new RescueAction(b.readUUID(),b.readBoolean()); }
            public void encode(RegistryFriendlyByteBuf b, RescueAction p) { b.writeUUID(p.target); b.writeBoolean(p.cancel); }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    // Snapshot.TYPE and Rescue.NONE can initialize the outer class first. Do not read their
    // defaults until that initialization has finished, regardless of which payload loads first.
    private static final class ClientState {
        private static volatile Snapshot latest = Snapshot.EMPTY;
    }
    private static Consumer<Snapshot> snapshots = ignored -> {};
    private static Consumer<Rescue> rescueUpdates = ignored -> {};
    private static Consumer<Event> events = ignored -> {};
    private static BiPredicate<UUID,Connection> clientGate = (session,connection) -> false;
    public static Snapshot latest() { return ClientState.latest; }
    public static void clearClient() { ClientState.latest = Snapshot.EMPTY; rescueUpdates.accept(Rescue.NONE); }
    public static boolean clientDowned(UUID id) { return latest().players.stream().anyMatch(p -> p.id.equals(id) && p.downed); }
    public static void setClientHandlers(Consumer<Snapshot> snapshot, Consumer<Event> event) { snapshots = snapshot; events = event; }
    public static void setClientRescueHandler(Consumer<Rescue> handler) { rescueUpdates = handler; }
    public static void setClientSessionGate(BiPredicate<UUID,Connection> gate) { clientGate=gate; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2-combat");
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (p,c) -> {
            Connection connection=c.connection();
            c.enqueueWork(() -> {
                Snapshot current=latest();
                if(clientGate.test(p.session,connection)&&(!current.session.equals(p.session)||p.sequence>=current.sequence)) {
                    ClientState.latest=p;rescueUpdates.accept(p.rescue);snapshots.accept(p);
                }
            });
        });
        registrar.playToClient(EventMessage.TYPE, EventMessage.CODEC, (p,c) -> {
            Connection connection=c.connection();
            c.enqueueWork(() -> {if(clientGate.test(p.event.session,connection)&&latest().session.equals(p.event.session))events.accept(p.event);});
        });
        registrar.playToServer(RescueAction.TYPE, RescueAction.CODEC, (p,c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer player) CombatService.rescue(player, p.target, p.cancel);
        }));
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) { return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg",path)); }
    private static Actor readActor(RegistryFriendlyByteBuf b) { return new Actor(b.readUUID(),b.readUtf(64),b.readVarInt(),b.readInt()); }
    private static void writeActor(RegistryFriendlyByteBuf b,Actor p) { b.writeUUID(p.id); b.writeUtf(p.name,64); b.writeVarInt(p.team); b.writeInt(p.color); }
    private static Rescue readRescue(RegistryFriendlyByteBuf b) { return new Rescue(b.readUUID(),b.readUtf(64),b.readUUID(),b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readBoolean()); }
    private static void writeRescue(RegistryFriendlyByteBuf b,Rescue p) { b.writeUUID(p.target); b.writeUtf(p.targetName,64); b.writeUUID(p.rescuer); b.writeUtf(p.rescuerName,64); b.writeVarInt(p.elapsedTicks); b.writeVarInt(p.durationTicks); b.writeBoolean(p.active); }
    private CombatPayloads() {}
}
