package com.example.examplemod.visibility;

import com.tacz.guns.block.entity.TargetBlockEntity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Scans only already loaded chunks; never requests a ticket, generates or loads a chunk. */
public final class VisibilityService {
    private static final Map<ServerLevel, Set<Long>> CHUNKS = new WeakHashMap<>();
    private static final Map<MinecraftServer, Map<ServerPlayer, Long>> SENT = new WeakHashMap<>();
    private static boolean registered;
    private VisibilityService() {}
    public static void register(IEventBus bus) {
        if (registered) return;
        registered = true;
        bus.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk)
                CHUNKS.computeIfAbsent(level, ignored -> new HashSet<>()).add(chunk.getPos().toLong());
        });
        bus.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                var chunks = CHUNKS.get(level);
                if (chunks != null) chunks.remove(event.getChunk().getPos().toLong());
            }
        });
        bus.addListener((ServerStoppedEvent event) -> {
            SENT.remove(event.getServer()); CHUNKS.keySet().removeIf(level -> level.getServer() == event.getServer());
        });
        bus.addListener((ServerTickEvent.Post event) -> {
            var server = event.getServer();
            if (server.getTickCount() % 20 != 0) return;
            syncSettings(server);
            for (var level : server.getAllLevels()) sendTargets(level);
        });
    }
    public static VisibilityPolicy.Settings settings(MinecraftServer server) { return VisibilitySavedData.get(server).settings(); }
    public static void syncSettings(MinecraftServer server) {
        var store = VisibilitySavedData.get(server);
        var sent = SENT.computeIfAbsent(server, ignored -> new HashMap<>());
        Set<ServerPlayer> present = new HashSet<>();
        for (var player : server.getPlayerList().getPlayers()) {
            present.add(player);
            if (sent.getOrDefault(player, -1L) != store.revision()) {
                PacketDistributor.sendToPlayer(player, VisibilityPayloads.state(store, VisibilityPayloads.PUSH,
                        store.loadError().isEmpty(), store.loadError()));
                sent.put(player, store.revision());
            }
        }
        sent.keySet().retainAll(present);
    }
    private record Candidate(TargetBlockEntity target, double distanceSquared) {}
    private static void sendTargets(ServerLevel level) {
        if (level.players().isEmpty()) return;
        int distance = settings(level.getServer()).targets();
        Set<Long> chunks = CHUNKS.getOrDefault(level, Set.of());
        var targets = new ArrayList<TargetBlockEntity>();
        for (long key : chunks) {
            var pos = new ChunkPos(key);
            // Cached FULL chunk lookup: unlike getChunk, this never requests loading.
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) continue;
            for (var blockEntity : chunk.getBlockEntities().values())
                if (blockEntity instanceof TargetBlockEntity target && !target.isRemoved()) targets.add(target);
        }
        for (var player : level.players()) {
            var candidates = new ArrayList<Candidate>();
            for (var target : targets) {
                double sq = target.getBlockPos().getCenter().distanceToSqr(player.position());
                if (VisibilityPolicy.inRange(sq, distance)) candidates.add(new Candidate(target, sq));
            }
            candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared)
                    .thenComparingLong(candidate -> candidate.target.getBlockPos().asLong()));
            var snapshots = new ArrayList<VisibilityPayloads.Target>();
            int omitted = Math.max(0, candidates.size() - VisibilityPolicy.MAX_TARGETS);
            for (int i = 0; i < Math.min(VisibilityPolicy.MAX_TARGETS, candidates.size()); i++) {
                TargetBlockEntity target = candidates.get(i).target;
                byte[] nbt;
                try {
                    var out = new ByteArrayOutputStream();
                    NbtIo.write(target.getUpdateTag(level.registryAccess()), new DataOutputStream(out));
                    nbt = out.toByteArray();
                    // Oversized owner/name data is kept on the server; render the standard model remotely.
                    if (nbt.length > VisibilityPolicy.MAX_TARGET_NBT_BYTES) {
                        out.reset(); NbtIo.write(new net.minecraft.nbt.CompoundTag(), new DataOutputStream(out)); nbt = out.toByteArray();
                    }
                } catch (IOException | RuntimeException error) { omitted++; continue; }
                BlockPos pos = target.getBlockPos();
                int light = (level.getBrightness(LightLayer.SKY, pos) << 20) | (level.getBrightness(LightLayer.BLOCK, pos) << 4);
                snapshots.add(new VisibilityPayloads.Target(pos, Block.getId(target.getBlockState()), light, nbt));
            }
            PacketDistributor.sendToPlayer(player, new VisibilityPayloads.Targets(level.dimension().location(),
                    Integer.toUnsignedLong(level.getServer().getTickCount()), snapshots, omitted));
        }
    }
}
