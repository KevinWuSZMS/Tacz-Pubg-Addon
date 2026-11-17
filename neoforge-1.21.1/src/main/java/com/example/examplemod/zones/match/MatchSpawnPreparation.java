package com.example.examplemod.zones.match;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Loads a bounded number of staging chunks per tick, then reserves every seat before moving anyone. */
public final class MatchSpawnPreparation {
    private static final TicketType<UUID> TICKET = TicketType.<UUID>create("tacz_pubg_staging", Comparator.naturalOrder());
    private static final Map<MinecraftServer, Map<UUID, Task>> TASKS = new WeakHashMap<>();
    private static final int TIMEOUT_TICKS = 2400;
    private static final int MAX_HELD_CHUNKS = 512;
    private MatchSpawnPreparation() {}

    private static final class Task {
        final Plan plan;
        final List<UUID> members;
        final Consumer<Plan> ready;
        final Consumer<String> failed;
        final net.minecraft.util.RandomSource random;
        final Set<Long> tried = new HashSet<>();
        final int maxAttempts;
        int deadline, attempts;
        BlockPos pending;
        Task(Plan plan, List<UUID> members, Consumer<Plan> ready, Consumer<String> failed) {
            this.plan = plan; this.members = List.copyOf(members); this.ready = ready; this.failed = failed;
            this.random = net.minecraft.util.RandomSource.create(plan.level.getRandom().nextLong());
            this.maxAttempts = Math.max(4096, members.size() * 256);
            this.deadline = plan.server.getTickCount() + TIMEOUT_TICKS;
        }
    }

    public static UUID begin(MinecraftServer server, MapDefinition map, List<UUID> plannedMembers,
            Consumer<Plan> ready, Consumer<String> failed) {
        requireThread(server);
        MatchService.requireStartAllowed(server);
        if (map.stagingSpawn() == null || plannedMembers.isEmpty()
                || new HashSet<>(plannedMembers).size() != plannedMembers.size())
            throw changed();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension()));
        if (level == null) throw changed();
        Plan plan = new Plan(server, level, map);
        TASKS.computeIfAbsent(server, ignored -> new LinkedHashMap<>())
                .put(plan.token, new Task(plan, plannedMembers, ready, failed));
        return plan.token;
    }

    public static void tick(MinecraftServer server) {
        var tasks = TASKS.get(server);
        if (tasks == null || tasks.isEmpty()) return;
        requireThread(server);
        if (!server.tickRateManager().runsNormally()) {
            for (Task task : tasks.values()) task.deadline++;
            return;
        }
        int[] budget = {2, 256}; // New chunk requests and candidate checks, shared by all preparing maps.
        for (Task task : new ArrayList<>(tasks.values())) {
            if (tasks.get(task.plan.token) != task) continue;
            if (!MapResetService.starting(server, task.plan.map.id())) {
                tasks.remove(task.plan.token); task.plan.rollback(); continue;
            }
            try {
                if (server.getTickCount() >= task.deadline)
                    throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_staging.timeout"));
                advance(task, budget);
                if (task.plan.positions.size() == task.members.size()) {
                    tasks.remove(task.plan.token);
                    task.ready.accept(task.plan);
                }
            } catch (RuntimeException error) {
                tasks.remove(task.plan.token);
                task.plan.rollback();
                task.failed.accept(error.getMessage() == null
                        ? Texts.tr("message.tacz_pubg.map_staging.changed") : error.getMessage());
            }
        }
    }

    private static void advance(Task task, int[] budget) {
        Plan plan = task.plan;
        var circle = plan.map.stagingSpawn();
        while (budget[1]-- > 0 && plan.positions.size() < task.members.size()) {
            if (task.pending == null) {
                if (++task.attempts > task.maxAttempts)
                    throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_staging.no_space"));
                double angle = task.random.nextDouble() * Math.PI * 2;
                double radius = Math.sqrt(task.random.nextDouble()) * circle.radius();
                int x = (int) Math.floor(circle.centerX() + Math.cos(angle) * radius);
                int z = (int) Math.floor(circle.centerZ() + Math.sin(angle) * radius);
                if (!circle.contains(x + .5, z + .5) || !task.tried.add(ChunkPos.asLong(x, z))) continue;
                task.pending = new BlockPos(x, 0, z);
            }
            int x = task.pending.getX(), z = task.pending.getZ();
            ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
            CompletableFuture<?> loading = plan.chunks.get(chunk.toLong());
            if (loading == null) {
                if (budget[0] <= 0) return;
                if (plan.chunks.size() >= MAX_HELD_CHUNKS)
                    throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_staging.no_space"));
                budget[0]--;
                plan.level.getChunkSource().addRegionTicket(TICKET, chunk, 2, plan.token);
                // Keep the ticket even if the request throws, so rollback can always release it.
                plan.chunks.put(chunk.toLong(), CompletableFuture.completedFuture(null));
                loading = plan.level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true);
                plan.chunks.put(chunk.toLong(), loading);
            }
            if (!loading.isDone()) return;
            if (loading.isCompletedExceptionally() || loading.isCancelled())
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_staging.chunk_failed"));
            var loaded = plan.level.getChunkSource().getChunkNow(chunk.x, chunk.z);
            if (loaded == null)
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.map_staging.chunk_failed"));
            task.pending = null;
            int y = loaded.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
            Vec3 position = new Vec3(x + .5, y, z + .5);
            if (!safePosition(plan.level, plan.map, position)
                    || plan.positions.values().stream().anyMatch(other -> other.distanceToSqr(position) < 2.25)
                    || occupied(plan.server, plan.level, position, task.members)) continue;
            plan.positions.put(task.members.get(plan.positions.size()), position);
        }
    }

    /** Uses only an already loaded column, including clearance, support, fluid and dangerous block checks. */
    public static boolean safePosition(ServerLevel level, MapDefinition map, Vec3 position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || !map.contains(position.x, position.z)
                || map.stagingSpawn() != null && !map.stagingSpawn().contains(position.x, position.z)) return false;
        BlockPos feet = BlockPos.containing(position), head = feet.above(), floor = feet.below();
        if (feet.getY() <= level.getMinBuildHeight() || feet.getY() + 2 >= level.getMaxBuildHeight()
                || level.getChunkSource().getChunkNow(feet.getX() >> 4, feet.getZ() >> 4) == null) return false;
        if (!level.getWorldBorder().isWithinBounds(feet)) return false;
        BlockState a = level.getBlockState(feet), b = level.getBlockState(head), c = level.getBlockState(floor);
        if (!a.getFluidState().isEmpty() || !b.getFluidState().isEmpty() || !c.getFluidState().isEmpty()
                || hazard(a) || hazard(b) || hazard(c)) return false;
        var support = c.getCollisionShape(level, floor);
        return a.getCollisionShape(level, feet).isEmpty() && b.getCollisionShape(level, head).isEmpty()
                && !support.isEmpty() && support.bounds().maxY >= .9;
    }

    private static boolean hazard(BlockState state) {
        return state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW);
    }
    private static boolean occupied(MinecraftServer server, ServerLevel level, Vec3 position, List<UUID> moving) {
        return server.getPlayerList().getPlayers().stream().anyMatch(player -> player.serverLevel() == level
                && !moving.contains(player.getUUID()) && player.position().distanceToSqr(position) < 2.25);
    }

    public static void cancel(MinecraftServer server, UUID token) {
        requireThread(server);
        var tasks = TASKS.get(server);
        Task task = tasks == null ? null : tasks.remove(token);
        if (task != null) task.plan.rollback();
    }
    public static void cancelMap(MinecraftServer server, long mapId) {
        requireThread(server);
        var tasks = TASKS.get(server);
        if (tasks == null) return;
        for (Task task : new ArrayList<>(tasks.values())) if (task.plan.map.id() == mapId)
            cancel(server, task.plan.token);
    }
    public static void stopped(MinecraftServer server) {
        var tasks = TASKS.remove(server);
        if (tasks != null) for (Task task : tasks.values()) task.plan.rollback();
    }

    public static final class Plan {
        private final UUID token = UUID.randomUUID();
        private final MinecraftServer server;
        private final ServerLevel level;
        private final MapDefinition map;
        private final Map<UUID, Vec3> positions = new LinkedHashMap<>();
        private final Map<Long, CompletableFuture<?>> chunks = new LinkedHashMap<>();
        private final Map<UUID, Original> originals = new LinkedHashMap<>();
        private boolean closed;
        private record Original(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {}
        private Plan(MinecraftServer server, ServerLevel level, MapDefinition map) {
            this.server = server; this.level = level; this.map = map;
        }
        public Map<UUID, Vec3> positions() { return Map.copyOf(positions); }

        /** Checks the whole human roster again before the first teleport; rollback restores original dimensions too. */
        public void teleportHumans(List<UUID> humans) {
            requireThread(server);
            MatchService.requireStartAllowed(server);
            if (closed || !originals.isEmpty() || new HashSet<>(humans).size() != humans.size()) throw changed();
            List<ServerPlayer> players = new ArrayList<>();
            for (UUID id : humans) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                Vec3 position = positions.get(id);
                if (player == null || !player.isAlive() || player.isRemoved() || MatchService.participantRunning(server, id)
                        || position == null || !safePosition(level, map, position)
                        || occupied(server, level, position, humans)) throw changed();
                players.add(player);
            }
            for (ServerPlayer player : players) originals.put(player.getUUID(),
                    new Original(player.serverLevel().dimension(), player.position(), player.getYRot(), player.getXRot()));
            try {
                for (ServerPlayer player : players) {
                    Vec3 position = positions.get(player.getUUID());
                    move(player, level, position, player.getYRot(), player.getXRot());
                    if (player.isRemoved() || player.serverLevel() != level || player.position().distanceToSqr(position) > .01)
                        throw changed();
                }
            } catch (RuntimeException error) { rollback(); throw error; }
        }
        public void commit() {
            requireThread(server); originals.clear(); release();
        }
        public void rollback() {
            requireThread(server);
            for (var entry : originals.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                Original original = entry.getValue();
                ServerLevel oldLevel = server.getLevel(original.dimension());
                if (player != null && oldLevel != null) try {
                    move(player, oldLevel, original.position(), original.yaw(), original.pitch());
                } catch (RuntimeException error) { LogUtils.getLogger().warn("Could not roll back staging teleport for {}", entry.getKey(), error); }
            }
            originals.clear(); release();
        }
        private void release() {
            if (closed) return;
            closed = true;
            for (long packed : chunks.keySet())
                level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(packed), 2, token);
            chunks.clear();
        }
        private static void move(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch) {
            player.stopRiding();
            player.teleportTo(level, position.x, position.y, position.z, yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0;
        }
    }
    private static IllegalStateException changed() {
        return new IllegalStateException(Texts.tr("message.tacz_pubg.map_staging.changed"));
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Staging preparation must run on the server thread");
    }
}
