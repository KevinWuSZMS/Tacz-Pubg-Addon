package com.example.examplemod.backup;

import com.example.examplemod.backup.mixin.EntityBaselineLevelAccessor;
import com.example.examplemod.backup.mixin.EntityBaselineStorageAccessor;
import com.example.examplemod.backup.mixin.EntityBaselineEntityStorageAccessor;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Persistent non-player entity baseline and per-round ownership.
 * Chunk loading is asynchronous; world/entity/NBT mutation stays on the server thread.
 */
public final class EntityBaselineService {
    private static final String OWNER = "tacz_pubg_entity_round";
    private static final Map<MinecraftServer, State> STATES = new ConcurrentHashMap<>();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("tacz_pubg_entity_baseline",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final ExecutorService LOADS = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "tacz-pubg-entity-baseline-load"); thread.setDaemon(true); return thread;
    });
    // Disk probing can decompress legacy terrain records. Keep it off the small chunk
    // request pool so several captures cannot starve an already-running restoration.
    private static final ExecutorService INDEXES = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "tacz-pubg-entity-baseline-index"); thread.setDaemon(true); return thread;
    });
    private static final class State {
        final MapResetData data;
        final ArrayDeque<Entity> watched = new ArrayDeque<>();
        final Map<UUID, Entity> watching = new HashMap<>();
        final Map<Long, Task> tasks = new HashMap<>();
        final Map<Long, Sweep> preparations = new HashMap<>();
        final Set<Long> prepared = new HashSet<>();
        final Set<UUID> spawning = new HashSet<>();
        State(MapResetData data) { this.data = data; }
    }
    private EntityBaselineService() {}

    static void register(IEventBus bus) {
        bus.addListener(EntityBaselineService::joined);
        bus.addListener(EntityBaselineService::left);
        bus.addListener((EntityTickEvent.Pre event) -> {
            if (freezeTick(event.getEntity())) event.setCanceled(true);
        });
        bus.addListener((EntityTickEvent.Post event) -> {
            var entity = event.getEntity();
            if (eligible(entity) && entity.level() instanceof ServerLevel level) {
                var state = STATES.get(level.getServer()); if (state != null) observe(state, entity);
            }
        });
    }
    /** Also used by ServerLevel's passenger path, which does not fire EntityTickEvent.Pre. */
    public static boolean freezeTick(Entity entity) {
        if (!eligible(entity) || !(entity.level() instanceof ServerLevel level)) return false;
        var state = STATES.get(level.getServer()); if (state == null) return false;
        observe(state, entity);
        if (entity.isRemoved()) return true;
        for (var entry : state.data.entries.values()) if (entry.managesEntities()
                && (inside(entry.map, entity) || entry.entities.containsKey(entity.getUUID()))
                && (entry.entityCapture || entry.state.equals("RESTORING") || entry.state.equals("FAILED"))) {
            if (entry.entityCapture && !entry.state.equals("FAILED")) {
                try { capture(state, entry, entity); }
                catch (RuntimeException error) {
                    // A broken mod serializer must fail the task, not crash the server's entity tick.
                    remove(level.getServer(), entry.map.id());
                    com.mojang.logging.LogUtils.getLogger().error("Map #{} entity capture failed", entry.map.id(), error);
                    level.getServer().getPlayerList().getPlayers().stream().filter(player -> player.hasPermissions(2))
                            .forEach(player -> player.sendSystemMessage(Texts.of("message.tacz_pubg.journal.failed", entry.map.id(), entry.failure)));
                }
            }
            return true;
        }
        return false;
    }
    /** Optional position hook: catches command/mod teleports before the next maintenance slice. */
    public static void afterPositionChanged(Entity entity) {
        if (!entity.isAddedToLevel() || !eligible(entity) || !(entity.level() instanceof ServerLevel level)) return;
        var state = STATES.get(level.getServer()); if (state != null) observe(state, entity);
    }
    static void initialize(MinecraftServer server, MapResetData data) {
        close(server);
        var state = new State(data); STATES.put(server, state);
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) watch(state, entity);
    }
    static void close(MinecraftServer server) {
        var state = STATES.remove(server);
        if (state != null) {
            state.tasks.values().forEach(Task::close);
            state.preparations.values().forEach(Sweep::close);
        }
    }
    static void remove(MinecraftServer server, long id) {
        var state = STATES.get(server); if (state == null) return;
        var task = state.tasks.remove(id); if (task != null) task.close();
        cancelPreparation(server, id);
    }
    static void cancelPreparation(MinecraftServer server, long id) {
        var state = STATES.get(server); if (state == null) return;
        var preparation = state.preparations.remove(id); if (preparation != null) preparation.close();
        state.prepared.remove(id);
    }
    static boolean prepareTick(MinecraftServer server, MapResetData.Entry entry, long deadline) {
        if (!entry.managesEntities()) return true;
        var state = STATES.get(server);
        if (state.prepared.contains(entry.map.id())) return true;
        var sweep = state.preparations.computeIfAbsent(entry.map.id(), ignored -> new Sweep(server, entry, true));
        if (!sweep.tick(deadline, entity -> observe(state, entity))) return false;
        sweep.close(); state.preparations.remove(entry.map.id()); state.prepared.add(entry.map.id());
        return true;
    }
    private static boolean eligible(Entity entity) {
        // Both real players and Carpet fake players inherit Player. Transport formations are
        // non-persistent synthetic match entities and are owned by the flight service instead.
        return !(entity instanceof Player) && entity.getType().canSerialize();
    }
    private static boolean inside(MapDefinition map, Entity entity) {
        return map.dimension().equals(entity.level().dimension().location()) && map.contains(entity.getX(), entity.getZ());
    }
    private static void watch(State state, Entity entity) {
        if (!eligible(entity) || entity.isRemoved()) return;
        if (state.watching.put(entity.getUUID(), entity) != entity) state.watched.addLast(entity);
    }
    private static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !eligible(event.getEntity())) return;
        var state = STATES.get(level.getServer()); if (state == null || !state.data.readable()) return;
        var entity = event.getEntity();
        if (state.spawning.contains(entity.getUUID())) { watch(state, entity); return; }
        // This event may precede FULL chunk promotion: no chunk access or entity enumeration here.
        observe(state, entity);
        if (!entity.isRemoved() && !event.loadedFromDisk()) for (var entry : state.data.entries.values()) {
            if (entry.managesEntities() && inside(entry.map, entity) && (entry.entityCapture || entry.state.equals("RESTORING"))) {
                discard(entity); break; // Do not add new spawns to a baseline or a restoration in progress.
            }
        }
        if (entity.isRemoved()) event.setCanceled(true); else watch(state, entity);
    }
    private static void left(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !eligible(event.getEntity())) return;
        var state = STATES.get(level.getServer()); if (state == null) return;
        var entity = event.getEntity();
        rememberBaselineLocations(state, entity);
        var owner = entity.getPersistentData().getCompound(OWNER);
        var entry = state.data.entries.get(owner.getLong("map"));
        if (entry != null && entry.managesEntities() && sameMap(entry, owner) && owner.hasUUID("round") && owner.getUUID("round").equals(entry.entityRound))
            remember(state, entry, entity);
        state.watching.remove(entity.getUUID(), entity);
    }
    private static void observe(State state, Entity entity) {
        if (entity.isRemoved() || !state.data.readable() || state.spawning.contains(entity.getUUID())) return;
        rememberBaselineLocations(state, entity);
        var owner = entity.getPersistentData().getCompound(OWNER);
        if (owner.hasUUID("round")) {
            var entry = state.data.entries.get(owner.getLong("map"));
            if (entry != null && sameMap(entry, owner)) {
                // IGNORE and an explicitly skipped replay leave entities and their saved
                // ownership untouched. A future RECORD choice gets a fresh map identity.
                if (!entry.managesEntities()) return;
                if (!owner.getUUID("round").equals(entry.entityRound)
                        || !(entry.state.equals("ACTIVE") || entry.state.equals("PREPARED"))
                        || !inside(entry.map, entity)) {
                    discard(entity); return;
                }
                remember(state, entry, entity); return;
            }
            // Deleted/recreated maps may share a displayed ID, but never entity ownership.
            entity.getPersistentData().remove(OWNER);
        }
        // Baseline originals can have moved away during protection. They still belong to this
        // map's next round; the preparation sweep also visits their persisted last locations.
        for (var entry : state.data.entries.values())
            if (entry.managesEntities() && entry.entities.containsKey(entity.getUUID())) {
                if (entry.state.equals("ACTIVE") || entry.state.equals("PREPARED")) {
                    claim(state, entry, entity);
                    if (!inside(entry.map, entity)) discard(entity);
                    return;
                }
                var task = state.tasks.get(entry.map.id());
                if (entry.state.equals("RESTORING") && task != null && task.validated
                        && !task.restored.contains(entity.getUUID())) { discard(entity); return; }
            }
        for (var entry : state.data.entries.values()) if (entry.managesEntities() && inside(entry.map, entity)) {
            if (entry.state.equals("ACTIVE") || entry.state.equals("PREPARED")) {
                claim(state, entry, entity); return;
            }
            var task = state.tasks.get(entry.map.id());
            if (entry.state.equals("RESTORING") && task != null && task.validated
                    && !task.restored.contains(entity.getUUID())) { discard(entity); return; }
        }
    }
    private static void claim(State state, MapResetData.Entry entry, Entity entity) {
        var mark = new CompoundTag(); mark.putLong("map", entry.map.id()); mark.putUUID("round", entry.entityRound);
        if (entry.identity != null) mark.putUUID("map_identity", entry.identity);
        entity.getPersistentData().put(OWNER, mark); remember(state, entry, entity);
    }
    private static boolean sameMap(MapResetData.Entry entry, CompoundTag owner) {
        return owner.getLong("map") == entry.map.id() && (entry.identity == null
                ? !owner.contains("map_identity")
                : owner.hasUUID("map_identity") && entry.identity.equals(owner.getUUID("map_identity")));
    }
    private static void rememberBaselineLocations(State state, Entity entity) {
        for (var entry : state.data.entries.values()) {
            if (!entry.managesEntities()) continue;
            var record = entry.entities.get(entity.getUUID()); if (record == null) continue;
            String dimension = entity.level().dimension().location().toString();
            long chunk = entity.chunkPosition().toLong();
            if (!record.getString("last_dimension").equals(dimension) || !record.contains("last_chunk")
                    || record.getLong("last_chunk") != chunk) {
                record.putString("last_dimension", dimension); record.putLong("last_chunk", chunk); state.data.setDirty();
            }
        }
    }
    private static void remember(State state, MapResetData.Entry entry, Entity entity) {
        var record = new CompoundTag(); record.putUUID("uuid", entity.getUUID());
        record.putString("dimension", entity.level().dimension().location().toString());
        record.putLong("chunk", entity.chunkPosition().toLong());
        var old = entry.ownedEntities.put(entity.getUUID(), record);
        if (!record.equals(old)) state.data.setDirty();
    }
    private static void discard(Entity entity) {
        // Entity.discard() delegates to subclass remove(): vanilla chest boats/minecarts drop
        // inventories there, and LivingEntity runs on-removal death effects. Apply the same
        // terminal DISCARDED reason directly; this still unregisters ticking/tracking/storage
        // through EntityInLevelCallback, without those destructive gameplay callbacks.
        entity.ejectPassengers(); entity.stopRiding(); entity.setRemoved(Entity.RemovalReason.DISCARDED);
    }
    static void maintain(MinecraftServer server, long deadline) {
        var state = STATES.get(server); if (state == null || !state.data.readable()) return;
        if (server.isPaused() && (!state.tasks.isEmpty() || !state.preparations.isEmpty())) {
            for (var level : server.getAllLevels())
                ((EntityBaselineLevelAccessor) level).taczPubg$entityManager().tick();
        }
        int remaining = Math.min(512, state.watched.size());
        while (remaining-- > 0 && System.nanoTime() < deadline) {
            var entity = state.watched.removeFirst();
            if (entity.isRemoved() || state.watching.get(entity.getUUID()) != entity) continue;
            observe(state, entity);
            if (!entity.isRemoved()) state.watched.addLast(entity);
        }
    }
    static boolean captureTick(MinecraftServer server, MapResetData.Entry entry, long deadline) {
        if (!entry.managesEntities()) return true;
        var state = STATES.get(server);
        var task = state.tasks.computeIfAbsent(entry.map.id(), ignored -> new Task(server, state, entry, true));
        if (!task.captureTick(deadline)) return false;
        entry.entityCapture = false; entry.state = "PROTECTED"; state.data.setDirty();
        remove(server, entry.map.id()); return true;
    }
    static boolean clearTick(MinecraftServer server, MapResetData.Entry entry, long deadline) {
        if (!entry.managesEntities()) return true;
        var state = STATES.get(server);
        var task = state.tasks.computeIfAbsent(entry.map.id(), ignored -> new Task(server, state, entry, false));
        return task.clearTick(deadline);
    }
    static boolean restoreTick(MinecraftServer server, MapResetData.Entry entry, long deadline) {
        if (!entry.managesEntities()) return true;
        var state = STATES.get(server); var task = state.tasks.get(entry.map.id());
        if (!task.restoreTick(deadline)) return false;
        remove(server, entry.map.id()); return true;
    }
    private static void capture(State state, MapResetData.Entry entry, Entity entity) {
        if (!eligible(entity) || entity.isRemoved() || !inside(entry.map, entity) || entry.entities.containsKey(entity.getUUID())) return;
        try {
            var nbt = new CompoundTag();
            if (!entity.saveAsPassenger(nbt)) throw failed(entity.getUUID());
            nbt.remove("Passengers");
            // Vanilla serializes a passenger at its vehicle's X/Z. Retain the actual captured
            // position as well, including the case where its vehicle lies outside this map.
            var position = new ListTag();
            position.add(DoubleTag.valueOf(entity.getX())); position.add(DoubleTag.valueOf(entity.getY()));
            position.add(DoubleTag.valueOf(entity.getZ())); nbt.put("Pos", position);
            nbt.getCompound("NeoForgeData").remove(OWNER);
            var record = new CompoundTag(); record.putUUID("uuid", entity.getUUID()); record.put("nbt", nbt);
            record.putString("last_dimension", entity.level().dimension().location().toString());
            record.putLong("last_chunk", entity.chunkPosition().toLong());
            if (entity.getVehicle() != null && eligible(entity.getVehicle())) {
                record.putUUID("vehicle", entity.getVehicle().getUUID());
                record.putInt("passenger_index", entity.getVehicle().getPassengers().indexOf(entity));
            }
            entry.entities.put(entity.getUUID(), record); state.data.setDirty();
        } catch (RuntimeException error) {
            entry.state = "FAILED"; entry.failure = String.valueOf(error.getMessage()); state.data.setDirty(); throw error;
        }
    }
    private static IllegalStateException failed(Object entity) {
        return new IllegalStateException(Texts.tr("message.tacz_pubg.entities.entity_failed", entity));
    }
    private record Place(ResourceLocation dimension, ChunkPos chunk) {}
    private static Place place(MapResetData.Entry entry, CompoundTag record) {
        var pos = record.getCompound("nbt").getList("Pos", Tag.TAG_DOUBLE);
        return new Place(entry.map.dimension(), new ChunkPos((int) Math.floor(pos.getDouble(0) / 16), (int) Math.floor(pos.getDouble(2) / 16)));
    }
    private static ServerLevel level(MinecraftServer server, ResourceLocation dimension) {
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.dimension_missing"));
        return level;
    }
    private static Entity find(MinecraftServer server, UUID uuid) {
        for (var level : server.getAllLevels()) { var entity = level.getEntity(uuid); if (entity != null) return entity; }
        return null;
    }

    /** A bounded window of FULL chunks; FULL alone is not proof that entity storage finished loading. */
    private static final class Loads implements AutoCloseable {
        private record Pending(ServerLevel level, CompletableFuture<?> future, long since) {}
        final MinecraftServer server;
        final LinkedHashMap<Place, Pending> pending = new LinkedHashMap<>();
        Loads(MinecraftServer server) { this.server = server; }
        void add(Place place) {
            if (pending.containsKey(place)) return;
            var level = level(server, place.dimension);
            var cp = place.chunk; level.getChunkSource().addRegionTicket(TICKET, cp, 0, cp);
            var future = CompletableFuture.supplyAsync(() ->
                    level.getChunkSource().getChunkFuture(cp.x, cp.z, ChunkStatus.FULL, true), LOADS).thenCompose(value -> value);
            pending.put(place, new Pending(level, future, System.nanoTime()));
        }
        boolean ready(Place place) {
            add(place); var load = pending.get(place);
            if (load.future.isDone()) {
                load.future.join();
                if (load.level.getChunkSource().getChunkNow(place.chunk.x, place.chunk.z) != null
                        && load.level.areEntitiesLoaded(place.chunk.toLong())) return true;
            }
            if (System.nanoTime() - load.since > 60_000_000_000L)
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.load_timeout", place.chunk));
            return false;
        }
        void release(Place place) {
            var load = pending.remove(place);
            if (load != null) {
                load.future.cancel(false);
                load.level.getChunkSource().removeRegionTicket(TICKET, place.chunk, 0, place.chunk);
            }
        }
        public void close() { for (var place : List.copyOf(pending.keySet())) release(place); }
    }

    /** Visits only entity-bearing candidates, including unloaded disk records and moved originals. */
    private static final class Sweep implements AutoCloseable {
        final MapResetData.Entry entry; final MinecraftServer server; final Loads loads;
        final EntityRegionCandidates.Bounds bounds;
        final Set<Place> candidates = new LinkedHashSet<>();
        final CompletableFuture<Set<Long>> diskIndex;
        final AtomicBoolean cancelled = new AtomicBoolean();
        List<Place> places;
        final ArrayDeque<Place> queued = new ArrayDeque<>();
        long next, completed, total;
        List<Entity> entities; int entityCursor;
        Sweep(MinecraftServer server, MapResetData.Entry entry, boolean includeOwned) {
            this.server = server; this.entry = entry; loads = new Loads(server);
            bounds = new EntityRegionCandidates.Bounds((int) Math.floor(entry.map.minX() / 16),
                    (int) Math.floor(entry.map.minZ() / 16), (int) Math.floor(entry.map.maxX() / 16),
                    (int) Math.floor(entry.map.maxZ() / 16));
            loadedCandidates();
            if (includeOwned) for (var owned : entry.ownedEntities.values()) {
                var place = new Place(ResourceLocation.parse(owned.getString("dimension")), new ChunkPos(owned.getLong("chunk")));
                candidates.add(place);
            }
            if (includeOwned) for (var record : entry.entities.values()) {
                var place = record.contains("last_dimension") && record.contains("last_chunk")
                        ? new Place(ResourceLocation.parse(record.getString("last_dimension")), new ChunkPos(record.getLong("last_chunk")))
                        : place(entry, record);
                candidates.add(place);
            }
            var level = level(server, entry.map.dimension());
            var manager = (EntityBaselineStorageAccessor) ((EntityBaselineLevelAccessor) level).taczPubg$entityManager();
            var storage = manager.taczPubg$permanentStorage();
            if (!(storage instanceof EntityStorage))
                throw new IllegalStateException("Unsupported persistent entity storage " + storage.getClass().getName());
            // The IOWorker barrier includes records already unloaded but still pending write.
            // Never call EntityStorage.flush(): it also runs the deserializer mailbox inline.
            var barrier = ((EntityBaselineEntityStorageAccessor) storage).taczPubg$regionStorage().synchronize(false);
            var folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
            diskIndex = barrier.thenApplyAsync(ignored -> EntityRegionCandidates.collect(folder, bounds, cancelled::get), INDEXES);
        }
        private void loadedCandidates() {
            var manager = ((EntityBaselineLevelAccessor) level(server, entry.map.dimension())).taczPubg$entityManager();
            var storage = ((EntityBaselineStorageAccessor) manager).taczPubg$sectionStorage();
            for (long chunk : EntityChunkSnapshot.nonEmptyChunks(storage)) {
                var place = new ChunkPos(chunk);
                if (bounds.contains(place.x, place.z)) candidates.add(new Place(entry.map.dimension(), place));
            }
        }
        boolean tick(long deadline, Consumer<Entity> action) {
            if (places == null) {
                if (!diskIndex.isDone()) return false;
                for (long chunk : diskIndex.join()) candidates.add(new Place(entry.map.dimension(), new ChunkPos(chunk)));
                // Entities may have entered or unloaded while the read-only disk index ran.
                loadedCandidates();
                places = new ArrayList<>(candidates);
                places.sort(Comparator.comparing((Place value) -> value.dimension.toString())
                        .thenComparingInt(value -> value.chunk.z).thenComparingInt(value -> value.chunk.x));
                total = places.size();
            }
            int budget = 128, chunks = 4;
            while (completed < total && budget > 0 && chunks > 0 && System.nanoTime() < deadline) {
                while (queued.size() < 4 && next < total) { var place = places.get((int) next++); queued.addLast(place); loads.add(place); }
                var place = queued.getFirst();
                if (!loads.ready(place)) return false;
                if (entities == null) {
                    var manager = ((EntityBaselineLevelAccessor) level(server, place.dimension)).taczPubg$entityManager();
                    var storage = ((EntityBaselineStorageAccessor) manager).taczPubg$sectionStorage();
                    // Do not replace this with a tall AABB: Lithium's small-X/Z query path
                    // loops over every Y section, outside our deadline, even for an empty chunk.
                    entities = EntityChunkSnapshot.collect(storage, place.chunk.toLong());
                    entityCursor = 0;
                }
                while (entityCursor < entities.size() && budget-- > 0 && System.nanoTime() < deadline) {
                    var entity = entities.get(entityCursor++);
                    if (eligible(entity) && !entity.isRemoved() && entity.level().dimension().location().equals(place.dimension)
                            && entity.chunkPosition().equals(place.chunk)) action.accept(entity);
                }
                if (entityCursor < entities.size()) break;
                entities = null; queued.removeFirst(); loads.release(place); completed++; chunks--;
            }
            return completed == total;
        }
        public void close() { cancelled.set(true); diskIndex.cancel(false); loads.close(); }
    }

    private static final class Task implements AutoCloseable {
        final MinecraftServer server; final State state; final MapResetData.Entry entry;
        final ServerBossEvent bar;
        final Set<UUID> restored = new HashSet<>();
        Sweep sweep; Loads spawnLoads;
        final List<UUID> roots = new ArrayList<>();
        final Map<UUID, List<UUID>> passengers = new HashMap<>();
        boolean validated, cleared; int rootCursor, restoredCount, validationCursor;
        final List<CompoundTag> records;
        Task(MinecraftServer server, State state, MapResetData.Entry entry, boolean capture) {
            this.server = server; this.state = state; this.entry = entry;
            records = new ArrayList<>(entry.entities.values());
            bar = new ServerBossEvent(Texts.of(capture ? "message.tacz_pubg.entities.capture_progress"
                    : "message.tacz_pubg.entities.clear_progress", entry.map.id(), 0, 0, 0),
                    BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
            sweep = new Sweep(server, entry, !capture);
        }
        void audience() {
            for (var player : server.getPlayerList().getPlayers())
                if (player.hasPermissions(2) || player.level().dimension().location().equals(entry.map.dimension())) bar.addPlayer(player);
        }
        boolean captureTick(long deadline) {
            audience();
            boolean done = sweep.tick(deadline, entity -> capture(state, entry, entity));
            bar.setName(Texts.of("message.tacz_pubg.entities.capture_progress", entry.map.id(), sweep.completed, sweep.total, entry.entities.size()));
            bar.setProgress(sweep.total == 0 ? (done ? 1 : 0) : (float) ((double) sweep.completed / sweep.total));
            return done;
        }
        boolean validate(long deadline) {
            int budget = 128;
            while (validationCursor < records.size() && budget-- > 0 && System.nanoTime() < deadline) {
                var record = records.get(validationCursor++); var uuid = record.getUUID("uuid");
                var nbt = record.getCompound("nbt");
                if (EntityType.by(nbt).isEmpty() || EntityType.by(nbt).get() == EntityType.PLAYER) throw failed(uuid);
                if (record.hasUUID("vehicle") && entry.entities.containsKey(record.getUUID("vehicle"))) {
                    var vehicle = record.getUUID("vehicle");
                    passengers.computeIfAbsent(vehicle, ignored -> new ArrayList<>()).add(uuid);
                    var seen = new HashSet<UUID>(); var node = uuid;
                    while (entry.entities.containsKey(node) && entry.entities.get(node).hasUUID("vehicle")) {
                        if (!seen.add(node)) throw failed(uuid);
                        node = entry.entities.get(node).getUUID("vehicle");
                    }
                } else roots.add(uuid);
            }
            if (validationCursor != records.size()) return false;
            passengers.values().forEach(list -> list.sort(Comparator.comparingInt(
                    uuid -> entry.entities.get(uuid).getInt("passenger_index"))));
            return true;
        }
        boolean clearTick(long deadline) {
            audience();
            if (cleared) return true;
            if (!validated) { if (!validate(deadline)) return false; validated = true; }
            cleared = sweep.tick(deadline, entity -> {
                if (inside(entry.map, entity) || owned(entity) || entry.entities.containsKey(entity.getUUID())) removeTree(entity);
            });
            bar.setName(Texts.of("message.tacz_pubg.entities.clear_progress", entry.map.id(), sweep.completed, sweep.total));
            bar.setProgress(sweep.total == 0 ? (cleared ? 1 : 0) : (float) ((double) sweep.completed / sweep.total));
            if (cleared) { sweep.close(); spawnLoads = new Loads(server); bar.setVisible(false); }
            return cleared;
        }
        boolean owned(Entity entity) {
            var owner = entity.getPersistentData().getCompound(OWNER);
            return sameMap(entry, owner) && owner.hasUUID("round");
        }
        void removeTree(Entity entity) {
            for (var passenger : List.copyOf(entity.getPassengers()))
                if (eligible(passenger) && (inside(entry.map, passenger) || owned(passenger)
                        || entry.entities.containsKey(passenger.getUUID()))) removeTree(passenger);
            discard(entity);
        }
        CompoundTag tree(UUID uuid) {
            var nbt = entry.entities.get(uuid).getCompound("nbt").copy();
            nbt.getCompound("NeoForgeData").remove(OWNER);
            var list = new ListTag();
            for (var child : passengers.getOrDefault(uuid, List.of())) list.add(tree(child));
            if (!list.isEmpty()) nbt.put("Passengers", list);
            return nbt;
        }
        boolean restoreTick(long deadline) {
            audience(); bar.setVisible(true);
            int budget = 64;
            while (rootCursor < roots.size() && budget > 0 && System.nanoTime() < deadline) {
                var root = roots.get(rootCursor); var place = place(entry, entry.entities.get(root));
                if (!spawnLoads.ready(place)) break;
                var level = level(server, place.dimension);
                var entity = EntityType.loadEntityRecursive(tree(root), level, value -> value);
                if (entity == null || !eligible(entity)) throw failed(root);
                var group = entity.getSelfAndPassengers().toList();
                var expected = new HashSet<UUID>(); collect(root, expected);
                if (!expected.equals(new HashSet<>(group.stream().map(Entity::getUUID).toList()))) throw failed(root);
                for (var member : group) {
                    var record = entry.entities.get(member.getUUID());
                    if (record.hasUUID("vehicle") && expected.contains(record.getUUID("vehicle"))
                            && (member.getVehicle() == null || !member.getVehicle().getUUID().equals(record.getUUID("vehicle"))))
                        throw new IllegalStateException(Texts.tr("message.tacz_pubg.entities.passenger_failed", member.getUUID()));
                    var old = find(server, member.getUUID());
                    if (old != null) {
                        if (!(old instanceof Player) && (inside(entry.map, old) || owned(old))) discard(old);
                        else throw new IllegalStateException(Texts.tr("message.tacz_pubg.entities.uuid_conflict", member.getUUID()));
                    }
                }
                state.spawning.addAll(expected);
                var added = new ArrayList<Entity>();
                try {
                    for (var member : group) {
                        if (!level.addFreshEntity(member) || level.getEntity(member.getUUID()) != member) throw failed(member.getUUID());
                        added.add(member); restored.add(member.getUUID());
                    }
                } catch (RuntimeException error) {
                    added.forEach(EntityBaselineService::discard); restored.removeAll(expected); throw error;
                } finally { state.spawning.removeAll(expected); }
                budget -= group.size(); restoredCount += group.size(); rootCursor++; spawnLoads.release(place);
            }
            bar.setName(Texts.of("message.tacz_pubg.entities.restore_progress", entry.map.id(), restoredCount, entry.entities.size()));
            bar.setProgress(entry.entities.isEmpty() ? 1 : (float) restoredCount / entry.entities.size());
            return rootCursor == roots.size();
        }
        void collect(UUID root, Set<UUID> result) {
            if (!result.add(root)) throw failed(root);
            for (var child : passengers.getOrDefault(root, List.of())) collect(child, result);
        }
        public void close() { sweep.close(); if (spawnLoads != null) spawnLoads.close(); bar.removeAllPlayers(); }
    }
}
