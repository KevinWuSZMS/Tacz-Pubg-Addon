package com.example.examplemod.throwables;

import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.Set;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ThrowablePayloads {
    private static Consumer<Effect> effects = ignored -> {};
    private static Consumer<QuickUse> quickUse = ignored -> {};
    private static BiConsumer<ServerPlayer, String> quickActions = (player, action) -> {};
    private static final Set<String> QUICK_ACTIONS = Set.of("med_kit", "first_aid_kit", "bandage", "boost",
            "energy_drink", "painkiller", "adrenaline_syringe", "throwable",
            "frag_grenade", "stun_grenade", "smoke_grenade", "molotov", "sticky_bomb", "c4", "blue_zone_grenade");
    public record QuickAction(String action) implements CustomPacketPayload {
        public static final Type<QuickAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "quick_item_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuickAction> CODEC = new StreamCodec<>() {
            public QuickAction decode(RegistryFriendlyByteBuf b) { return new QuickAction(b.readUtf(32)); }
            public void encode(RegistryFriendlyByteBuf b, QuickAction p) { b.writeUtf(p.action, 32); }
        };
        public QuickAction { if (!QUICK_ACTIONS.contains(action)) throw new IllegalArgumentException("Invalid quick item action"); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record QuickUse(int slot, int duration) implements CustomPacketPayload {
        public static final Type<QuickUse> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "quick_item_use"));
        public static final StreamCodec<RegistryFriendlyByteBuf, QuickUse> CODEC = new StreamCodec<>() {
            public QuickUse decode(RegistryFriendlyByteBuf b) { return new QuickUse(b.readVarInt(), b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, QuickUse p) { b.writeVarInt(p.slot); b.writeVarInt(p.duration); }
        };
        public QuickUse { if (slot < 0 || slot > 8 || duration < 1 || duration > 1200) throw new IllegalArgumentException("Invalid quick use"); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Action(int action) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "throwable_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = new StreamCodec<>() {
            public Action decode(RegistryFriendlyByteBuf b) { return new Action(b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, Action p) { b.writeVarInt(p.action); }
        };
        public Action { if (action < 0 || action > 2) throw new IllegalArgumentException("Invalid throwable action"); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Flash, ringing and fuse updates; all durations originate at the server. */
    public record Effect(int flash, int ringing, int fuse) implements CustomPacketPayload {
        public static final Type<Effect> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "throwable_effect"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Effect> CODEC = new StreamCodec<>() {
            public Effect decode(RegistryFriendlyByteBuf b) { return new Effect(b.readVarInt(), b.readVarInt(), b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, Effect p) { b.writeVarInt(p.flash); b.writeVarInt(p.ringing); b.writeVarInt(p.fuse); }
        };
        public Effect {
            if (flash < 0 || flash > 160 || ringing < 0 || ringing > 200 || fuse < -2 || fuse > 72000)
                throw new IllegalArgumentException("Invalid throwable effect");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void setClientHandler(Consumer<Effect> handler) { effects = handler; }
    public static void setQuickUseClientHandler(Consumer<QuickUse> handler) { quickUse = handler; }
    public static void setQuickActionHandler(BiConsumer<ServerPlayer, String> handler) { quickActions = handler; }
    public static void quickUseStarted(ServerPlayer player, int duration) {
        player.inventoryMenu.broadcastChanges();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(player.getInventory().selected));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new QuickUse(player.getInventory().selected, duration));
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2-throwables");
        registrar.playToClient(Effect.TYPE, Effect.CODEC,
                (p, c) -> c.enqueueWork(() -> effects.accept(p)));
        registrar.playToClient(QuickUse.TYPE, QuickUse.CODEC, (p, c) -> c.enqueueWork(() -> quickUse.accept(p)));
        registrar.playToServer(QuickAction.TYPE, QuickAction.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer p)) return;
            var game = com.example.examplemod.zones.match.MatchService.participantGame(p.server, p.getUUID());
            if (p.isAlive() && !p.isSpectator() && p.server.tickRateManager().runsNormally()
                    && (game == null || !game.finished() && !game.paused() && game.alive(p.getUUID())
                    && game.elapsedTicks() >= game.settings().preparationSeconds() * 20L)) {
                if (ThrowableService.cooking(p)) ThrowableService.cancel(p);
                quickActions.accept(p, packet.action);
            }
        }));
        registrar.playToServer(Action.TYPE, Action.CODEC, (packet, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer p) {
                if (packet.action == 0) ThrowableService.cook(p);
                else if (packet.action == 2) ThrowableService.throwCooked(p);
                else {
                    ThrowableService.cancel(p);
                    if (p.isUsingItem() && p.getUseItem().getItem() instanceof com.example.examplemod.vitals.PubgMedicineItem)
                        p.releaseUsingItem();
                }
            }
        }));
    }
    private ThrowablePayloads() {}
}
