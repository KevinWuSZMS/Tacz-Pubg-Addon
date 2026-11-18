package com.example.examplemod.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Commands refer to a server snapshot; clients never supply authoritative stacks. */
public final class PubgInventoryPayloads {
    public enum Action { REFRESH, PICKUP, RIGHT_CLICK, MOVE, ATTACH, UNLOAD, DROP, REMOVE_BACKPACK }
    public record Nearby(int entityId, UUID uuid, ItemStack item, int quantity) {}
    public record Recent(ItemStack item, long order) {}
    private static Consumer<Snapshot> snapshots = ignored -> {};
    private static Consumer<Feedback> feedback = ignored -> {};
    private PubgInventoryPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", path));
    }
    /** UNLOAD target: -1 inventory, -2 nearby ground, or 0..2 destination gun; source is the original gun. */
    public record Command(Action action, long revision, boolean ground, int source, int target, String attachmentType)
            implements CustomPacketPayload {
        public static final Type<Command> TYPE = id("pubg_inventory_command");
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = new StreamCodec<>() {
            public Command decode(RegistryFriendlyByteBuf b) {
                return new Command(b.readEnum(Action.class), b.readVarLong(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readUtf(32));
            }
            public void encode(RegistryFriendlyByteBuf b, Command p) {
                b.writeEnum(p.action); b.writeVarLong(p.revision); b.writeBoolean(p.ground);
                b.writeVarInt(p.source); b.writeVarInt(p.target); b.writeUtf(p.attachmentType, 32);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Snapshot(long revision, List<Nearby> nearby, List<ItemStack> inventory, ItemStack backpack, List<Recent> recent)
            implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = id("pubg_inventory_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b) {
                long revision = b.readVarLong();
                int count = bounded(b, 64); List<Nearby> nearby = new ArrayList<>(count);
                for (int i = 0; i < count; i++) nearby.add(new Nearby(b.readVarInt(), b.readUUID(), ItemStack.STREAM_CODEC.decode(b), b.readVarInt()));
                List<ItemStack> inventory = new ArrayList<>(41);
                for (int i = 0; i < 41; i++) inventory.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
                ItemStack backpack = ItemStack.OPTIONAL_STREAM_CODEC.decode(b);
                count = bounded(b, 256); List<Recent> recent = new ArrayList<>(count);
                for (int i = 0; i < count; i++) recent.add(new Recent(ItemStack.STREAM_CODEC.decode(b), b.readVarLong()));
                return new Snapshot(revision, List.copyOf(nearby), List.copyOf(inventory), backpack, List.copyOf(recent));
            }
            public void encode(RegistryFriendlyByteBuf b, Snapshot p) {
                b.writeVarLong(p.revision); b.writeVarInt(p.nearby.size());
                for (Nearby n : p.nearby) {
                    b.writeVarInt(n.entityId); b.writeUUID(n.uuid); ItemStack.STREAM_CODEC.encode(b, n.item); b.writeVarInt(n.quantity);
                }
                for (ItemStack stack : p.inventory) ItemStack.OPTIONAL_STREAM_CODEC.encode(b, stack);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(b, p.backpack);
                b.writeVarInt(p.recent.size());
                for (Recent r : p.recent) { ItemStack.STREAM_CODEC.encode(b, r.item); b.writeVarLong(r.order); }
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Feedback(String message, ItemStack soundItem, boolean uninstall, int gunSlot) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE = id("pubg_inventory_feedback");
        public static final StreamCodec<RegistryFriendlyByteBuf, Feedback> CODEC = new StreamCodec<>() {
            public Feedback decode(RegistryFriendlyByteBuf b) {
                return new Feedback(b.readUtf(256), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readBoolean(), b.readVarInt());
            }
            public void encode(RegistryFriendlyByteBuf b, Feedback p) {
                b.writeUtf(p.message, 256); ItemStack.OPTIONAL_STREAM_CODEC.encode(b, p.soundItem);
                b.writeBoolean(p.uninstall); b.writeVarInt(p.gunSlot);
            }
        };
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static int bounded(RegistryFriendlyByteBuf b, int max) {
        int count = b.readVarInt();
        if (count < 0 || count > max) throw new IllegalArgumentException("Invalid inventory list size");
        return count;
    }
    public static void setClient(Consumer<Snapshot> state, Consumer<Feedback> result) { snapshots = state; feedback = result; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1-pubg-inventory-draft");
        registrar.playToServer(Command.TYPE, Command.CODEC, (p, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) PubgInventoryService.handle(player, p);
        }));
        registrar.playToClient(Snapshot.TYPE, Snapshot.CODEC, (p, context) -> context.enqueueWork(() -> snapshots.accept(p)));
        registrar.playToClient(Feedback.TYPE, Feedback.CODEC, (p, context) -> context.enqueueWork(() -> feedback.accept(p)));
    }
}
