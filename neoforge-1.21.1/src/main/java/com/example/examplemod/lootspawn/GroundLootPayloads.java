package com.example.examplemod.lootspawn;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;
import java.util.function.Consumer;

public final class GroundLootPayloads {
    private static Consumer<DropLanded> clientDropHandler = ignored -> {};
    private GroundLootPayloads() {}

    public static void setClientDropHandler(Consumer<DropLanded> handler) {
        clientDropHandler = handler;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("4");
        registrar.playToServer(Pickup.TYPE, Pickup.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.serverLevel().getEntity(payload.entityId()) instanceof GroundLootEntity loot
                    && GroundLootPickup.canPickup(player, loot)) {
                loot.pickup(player);
            }
        }));
        registrar.playToClient(DropLanded.TYPE, DropLanded.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> clientDropHandler.accept(payload)));
        registrar.playToClient(DropCatalogPart.TYPE, DropCatalogPart.CODEC,
                (payload, context) -> context.enqueueWork(() -> DroppedLootCatalog.receive(payload)));
    }

    public record Pickup(int entityId) implements CustomPacketPayload {
        public static final Type<Pickup> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "pickup_ground_loot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pickup> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Pickup::entityId, Pickup::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record DropCatalogPart(UUID id, long revision, int index, int parts, String text) implements CustomPacketPayload {
        public static final Type<DropCatalogPart> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                "tacz_pubg", "drop_catalog_part"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DropCatalogPart> CODEC = new StreamCodec<>() {
            @Override public DropCatalogPart decode(RegistryFriendlyByteBuf buffer) {
                return new DropCatalogPart(buffer.readUUID(), buffer.readVarLong(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readUtf(DroppedLootCatalog.PART_CHARACTERS));
            }
            @Override public void encode(RegistryFriendlyByteBuf buffer, DropCatalogPart payload) {
                buffer.writeUUID(payload.id()); buffer.writeVarLong(payload.revision());
                buffer.writeVarInt(payload.index()); buffer.writeVarInt(payload.parts());
                buffer.writeUtf(payload.text(), DroppedLootCatalog.PART_CHARACTERS);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Presentation only: all ownership, quantities and pickup checks stay server-side. */
    public record DropLanded(int sourceEntityId, UUID sourceUuid, int targetEntityId, UUID targetUuid,
            ItemStack item, Vec3 landing, float yaw, Vec3 velocity) implements CustomPacketPayload {
        public static final Type<DropLanded> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                "tacz_pubg", "drop_landed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DropLanded> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public DropLanded decode(RegistryFriendlyByteBuf buffer) {
                return new DropLanded(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(),
                        ItemStack.STREAM_CODEC.decode(buffer),
                        new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readFloat(),
                        new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, DropLanded payload) {
                buffer.writeVarInt(payload.sourceEntityId());
                buffer.writeUUID(payload.sourceUuid());
                buffer.writeVarInt(payload.targetEntityId());
                buffer.writeUUID(payload.targetUuid());
                ItemStack.STREAM_CODEC.encode(buffer, payload.item());
                buffer.writeDouble(payload.landing().x);
                buffer.writeDouble(payload.landing().y);
                buffer.writeDouble(payload.landing().z);
                buffer.writeFloat(payload.yaw());
                buffer.writeDouble(payload.velocity().x);
                buffer.writeDouble(payload.velocity().y);
                buffer.writeDouble(payload.velocity().z);
            }
        };

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
