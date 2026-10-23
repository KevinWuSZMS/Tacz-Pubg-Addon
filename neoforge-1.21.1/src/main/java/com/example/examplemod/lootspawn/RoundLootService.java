package com.example.examplemod.lootspawn;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RoundLootService {
    private static final String SEEN = "tacz_pubg_cleanup_seen";
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, "tacz_pubg");
    // Nullable during bootstrap: ItemStack.isEmpty also runs before registries exist.
    public static volatile DataComponentType<String> MARK;
    private static final Set<String> RETIRED = ConcurrentHashMap.newKeySet(), CLIENT_RETIRED = ConcurrentHashMap.newKeySet();
    private static final Map<MinecraftServer, RoundLootData> DATA = new HashMap<>();
    static {
        COMPONENTS.register("round_loot", () -> {
            var type = DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build();
            MARK = type; return type;
        });
    }
    private RoundLootService() {}
    public static void register(IEventBus mod, IEventBus game) {
        COMPONENTS.register(mod); mod.addListener(RoundLootService::registerPayload);
        game.addListener(RoundLootService::joined);
        game.addListener((net.neoforged.neoforge.event.tick.EntityTickEvent.Post e) -> {
            if (e.getEntity().level() instanceof ServerLevel l && ground(e.getEntity()) != null)
                e.getEntity().getPersistentData().putLong(SEEN, data(l.getServer()).serial);
        });
        game.addListener((EntityLeaveLevelEvent e) -> {
            if (e.getLevel() instanceof ServerLevel l && ground(e.getEntity()) != null)
                e.getEntity().getPersistentData().putLong(SEEN, data(l.getServer()).serial);
        });
        game.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                data(p.server).retired.forEach(token -> PacketDistributor.sendToPlayer(p, new Retired(token)));
                p.inventoryMenu.broadcastFullState();
            }
        });
        game.addListener((ServerStoppedEvent e) -> { DATA.remove(e.getServer()); RETIRED.clear(); });
    }
    private static RoundLootData data(MinecraftServer server) {
        return DATA.computeIfAbsent(server, s -> {
            var data = RoundLootData.get(s); RETIRED.addAll(data.retired); return data;
        });
    }
    public static void begin(MinecraftServer server, MapDefinition map) {
        var round = data(server).begin(map);
        var level = server.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, map.dimension()));
        if (level != null) for (var entity : level.getAllEntities()) {
            var stack = ground(entity);
            if (stack != null && map.contains(entity.getX(), entity.getZ())) mark(stack, round.token());
        }
    }
    public static void end(MinecraftServer server, MapDefinition map) {
        LootRespawnService.cancelMap(server, map);
        var round = data(server).end(map);
        if (round != null) {
            RETIRED.add(round.token());
            PacketDistributor.sendToAllPlayers(new Retired(round.token()));
        }
        // Invalidation covers every holder (including nested inventories); discard visible carriers now.
        for (var level : server.getAllLevels()) {
            var remove = new ArrayList<Entity>();
            for (var entity : level.getAllEntities()) {
                var stack = ground(entity);
                if (stack != null && (stack.isEmpty() || map.dimension().equals(level.dimension().location())
                        && map.contains(entity.getX(), entity.getZ()))) remove.add(entity);
            }
            remove.forEach(Entity::discard);
        }
        for (var player : server.getPlayerList().getPlayers()) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastFullState();
            if (player.containerMenu != player.inventoryMenu) player.inventoryMenu.broadcastFullState();
        }
    }
    private static ItemStack ground(Entity entity) {
        return entity instanceof ItemEntity item ? item.getItem() : entity instanceof GroundLootEntity loot ? loot.getItem() : null;
    }
    private static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var data = data(level.getServer()); // Populate tombstones before any loaded holder is used.
        var entity = event.getEntity(); var stack = ground(entity);
        if (stack == null) return;
        long seen = event.loadedFromDisk() ? entity.getPersistentData().getLong(SEEN) : data.serial;
        boolean stale = stack.isEmpty() || data.cleanups.values().stream().anyMatch(c ->
                seen < c.serial() && c.map().dimension().equals(level.dimension().location()) && c.map().contains(entity.getX(), entity.getZ()));
        if (stale) { event.setCanceled(true); entity.discard(); return; }
        entity.getPersistentData().putLong(SEEN, data.serial);
        for (var round : data.active.values()) if (round.map().dimension().equals(level.dimension().location())
                && round.map().contains(entity.getX(), entity.getZ())) { mark(stack, round.token()); break; }
    }
    private static void mark(ItemStack stack, String token) {
        if (!stack.isEmpty() && MARK != null && !stack.has(MARK))
            stack.set(MARK, token + "|" + BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
    /** Called without ItemStack accessors, avoiding recursion from the isEmpty hook. */
    public static boolean expired(String mark, Item item) {
        if (mark == null || mark.length() < 38 || item == null) return false;
        String token = mark.substring(0, 36);
        return (RETIRED.contains(token) || CLIENT_RETIRED.contains(token))
                && mark.substring(37).equals(BuiltInRegistries.ITEM.getKey(item).toString());
    }
    /** Recipe results can copy components from inputs; products are deliberately not descendants. */
    public static void product(ItemStack stack) { if (MARK != null && stack != ItemStack.EMPTY) stack.remove(MARK); }
    public static void clearClient() { CLIENT_RETIRED.clear(); }
    public record Retired(String token) implements CustomPacketPayload {
        static final Type<Retired> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tacz_pubg", "round_loot_retired"));
        static final StreamCodec<RegistryFriendlyByteBuf, Retired> CODEC = StreamCodec.of(
                (b, p) -> b.writeUUID(UUID.fromString(p.token)), b -> new Retired(b.readUUID().toString()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    private static void registerPayload(RegisterPayloadHandlersEvent e) {
        e.registrar("1").playToClient(Retired.TYPE, Retired.CODEC,
                (p, c) -> c.enqueueWork(() -> CLIENT_RETIRED.add(p.token)));
    }
}
