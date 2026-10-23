package com.example.examplemod.backup;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.example.examplemod.zones.MapZoneSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Protect -> record first writes during a round -> replay only those positions -> protect. */
public final class MapResetService {
    public static final String NOTICE = Texts.tr("message.tacz_pubg.journal.notice");
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, Runtime> RUNS = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Map<Long, LevelChunk>> CHUNKS = new ConcurrentHashMap<>();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("tacz_pubg_changed_blocks", Comparator.comparingLong(ChunkPos::toLong));
    private static final ExecutorService LOADS = Executors.newFixedThreadPool(2, r -> {
        var thread = new Thread(r, "tacz-pubg-journal-load"); thread.setDaemon(true); return thread;
    });
    private record Position(ServerLevel level, long pos) {}
    private record Launch(Runnable ready, Consumer<String> failed) {}
    private static final class Runtime {
        final MapResetData data;
        final Map<Position, CompoundTag> containers = new HashMap<>();
        final Map<Long, Restore> restores = new LinkedHashMap<>();
        final Map<Long, Launch> launches = new LinkedHashMap<>();
        Position applying;
        Runtime(MapResetData data) { this.data = data; }
    }
    private MapResetService() {}
    public static void register(IEventBus bus) {
        EntityBaselineService.register(bus);
        bus.addListener((ServerStartedEvent e) -> initialize(e.getServer()));
        bus.addListener((ServerTickEvent.Post e) -> tickMaintenance(e.getServer()));
        bus.addListener((ServerStoppedEvent e) -> {
            var run = RUNS.remove(e.getServer());
            if (run != null) run.restores.values().forEach(Restore::close);
            EntityBaselineService.close(e.getServer());
            CHUNKS.keySet().removeIf(level -> level.getServer() == e.getServer());
        });
        bus.addListener(MapResetService::chunkLoaded); bus.addListener(MapResetService::chunkUnloaded);
        bus.addListener((BlockEvent.BreakEvent e) -> { if (e.getLevel() instanceof ServerLevel l && isLocked(l, e.getPos())) e.setCanceled(true); });
        bus.addListener((BlockEvent.EntityPlaceEvent e) -> { if (e.getLevel() instanceof ServerLevel l && isLocked(l, e.getPos())) e.setCanceled(true); });
        bus.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (e.getLevel() instanceof ServerLevel l && (isLocked(l, e.getPos()) || isLocked(l, e.getPos().relative(e.getFace())))) e.setCanceled(true);
        });
        bus.addListener((ExplosionEvent.Detonate e) -> {
            if (e.getLevel() instanceof ServerLevel l) e.getAffectedBlocks().removeIf(p -> isLocked(l, p));
        });
        bus.addListener((PistonEvent.Pre e) -> {
            if (!(e.getLevel() instanceof ServerLevel level)) return;
            var resolver = e.getStructureHelper();
            if (isLocked(level, e.getPos()) || isLocked(level, e.getFaceOffsetPos())) { e.setCanceled(true); return; }
            if (resolver != null && resolver.resolve()) {
                var direction = e.getPistonMoveType().isExtend ? e.getDirection() : e.getDirection().getOpposite();
                if (resolver.getToDestroy().stream().anyMatch(p -> isLocked(level, p))
                        || resolver.getToPush().stream().anyMatch(p -> isLocked(level, p) || isLocked(level, p.relative(direction)))) e.setCanceled(true);
            }
        });
    }
    private static void initialize(MinecraftServer server) {
        var data = MapResetData.get(server); var run = new Runtime(data); RUNS.put(server, run);
        if (!data.readable()) { LOGGER.error("Map change journal is invalid; map protection remains enabled."); return; }
        for (var map : MapZoneSavedData.get(server).maps()) {
            if (!data.entries.containsKey(map.id())) { data.entries.put(map.id(), new MapResetData.Entry(map)); data.setDirty(); }
        }
        EntityBaselineService.initialize(server, data);
        CHUNKS.forEach((level, chunks) -> { if (level.getServer() == server) chunks.values().forEach(MapResetService::captureContainers); });
    }
    private static Runtime runtime(MinecraftServer server) {
        if (!RUNS.containsKey(server)) initialize(server);
        return RUNS.get(server);
    }
    public static void validateSave(MinecraftServer server, MapDefinition old, MapDefinition candidate) {
        var run = runtime(server); run.data.requireWritable();
        if (old != null) requireEditable(server, old.id());
        if (server.getLevel(ResourceKey.create(Registries.DIMENSION, candidate.dimension())) == null)
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.dimension_missing"));
        if (Math.ceil(candidate.minX()) >= Math.floor(candidate.maxX()) || Math.ceil(candidate.minZ()) >= Math.floor(candidate.maxZ()))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.invalid_bounds"));
        requireNoActiveOverlap(server, candidate);
    }
    public static void saved(MinecraftServer server, MapDefinition old, MapDefinition map) {
        var run = runtime(server); var entry = run.data.entries.get(map.id());
        if (old != null && entry != null && MapResetData.sameRange(entry.map, map)) entry.map = map;
        else {
            EntityBaselineService.remove(server, map.id());
            var restore = run.restores.remove(map.id()); if (restore != null) restore.close();
            run.launches.remove(map.id());
            var replacement = new MapResetData.Entry(map);
            boolean editing = entry != null && entry.state.equals("EDITING");
            double threshold = PubgSettingsService.server(server).number("map.entity_recording_prompt_side_length");
            replacement.entityRecording = MapResetData.recordingForSave(map, threshold, old != null ? entry : null);
            replacement.entityCapture = !editing && replacement.managesEntities();
            replacement.state = editing ? "EDITING" : replacement.entityCapture ? "CAPTURING" : "PROTECTED";
            run.data.entries.put(map.id(), replacement);
        }
        run.data.setDirty();
        CHUNKS.forEach((level, chunks) -> { if (level.getServer() == server) chunks.values().forEach(MapResetService::captureContainers); });
        closeProtectedMenus(server);
        announce(server, Texts.tr(run.data.entries.get(map.id()).state.equals("EDITING")
                ? "message.tacz_pubg.journal.unfrozen" : "message.tacz_pubg.journal.protected", map.id()));
    }
    public static void requireEditable(MinecraftServer server, long id) {
        var run = runtime(server); run.data.requireWritable(); var entry = run.data.entries.get(id);
        if (entry != null && !entry.state.equals("PROTECTED") && !entry.state.equals("EDITING"))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
    }
    /** Finish the previous restore's save boundary before allowing permanent world edits. */
    public static void unfreeze(MinecraftServer server, long id) {
        var run = runtime(server); run.data.requireWritable();
        var entry = run.data.entries.get(id);
        if (entry == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
        if (entry.state.equals("EDITING")) return;
        var editing = entry.editingCopy();
        requireNoBusyOverlap(server, entry.map);
        checkContainers(server);
        // Completed journals deliberately survive until chunks/entities have reached disk.
        // Flush those restored values before retiring their only recovery records.
        server.saveEverything(true, true, true);
        run.data.entries.put(id, editing);
        try { run.data.persist(server); }
        catch (RuntimeException error) {
            run.data.entries.put(id, entry); run.data.setDirty(); throw error;
        }
        EntityBaselineService.remove(server, id);
        run.containers.keySet().removeIf(p -> matches(editing, p.level, BlockPos.of(p.pos))
                && !isLocked(p.level, BlockPos.of(p.pos)));
    }
    /** Protect edited terrain immediately; capture its new entity baseline before any round. */
    public static void freeze(MinecraftServer server, long id) {
        var run = runtime(server); run.data.requireWritable();
        var entry = run.data.entries.get(id);
        if (entry == null || !entry.state.equals("EDITING"))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_editing"));
        requireNoBusyOverlap(server, entry.map);
        checkContainers(server);
        server.saveEverything(true, true, true);
        // Old container snapshots predate the edit session and must never restore its contents.
        refreshContainers(server, entry.map);
        entry.freeze();
        try { run.data.persist(server); }
        catch (RuntimeException error) {
            entry.state = "EDITING"; entry.entityCapture = false; run.data.setDirty(); throw error;
        }
        closeProtectedMenus(server);
    }
    public static void deleted(MinecraftServer server, long id) {
        EntityBaselineService.remove(server, id);
        var run = runtime(server);
        var restore = run.restores.remove(id); if (restore != null) restore.close();
        run.launches.remove(id);
        run.data.entries.remove(id); run.data.setDirty();
        run.containers.keySet().removeIf(p -> !managed(p.level, BlockPos.of(p.pos)));
    }
    public static String description(MinecraftServer server, long id) {
        var e = runtime(server).data.entries.get(id);
        String state = e == null ? "" : switch (e.state) {
            case "ACTIVE" -> Texts.tr("message.tacz_pubg.journal.state_active");
            case "EDITING" -> Texts.tr("message.tacz_pubg.journal.state_editing");
            case "CAPTURING" -> Texts.tr("message.tacz_pubg.entities.capturing");
            case "PREPARED" -> Texts.tr("message.tacz_pubg.journal.state_prepared");
            case "RESTORING" -> Texts.tr("message.tacz_pubg.journal.state_restoring");
            case "FAILED" -> Texts.tr("message.tacz_pubg.journal.state_failed");
            default -> Texts.tr("message.tacz_pubg.journal.state_protected");
        };
        return e == null ? NOTICE : Texts.tr("message.tacz_pubg.journal.status", id, state, e.changes.size())
                + " · " + Texts.tr(switch (e.entityRecording) {
                    case RECORD -> "message.tacz_pubg.journal.entity_recording_enabled";
                    case IGNORE -> "message.tacz_pubg.journal.entity_recording_disabled";
                    case PENDING -> "message.tacz_pubg.journal.entity_recording_pending";
                })
                + (e.entityRestoreSkipped ? " · " + Texts.tr("message.tacz_pubg.journal.entities_skipped") : "")
                + (e.terrainRestoreSkipped ? " · " + Texts.tr("message.tacz_pubg.journal.terrain_skipped") : "")
                + (e.failure.isEmpty() ? "" : " · " + e.failure);
    }
    public static boolean entityRecordingPending(MinecraftServer server, long id) {
        var entry = runtime(server).data.entries.get(id);
        return entry != null && entry.entityRecording == MapResetData.EntityRecording.PENDING;
    }
    /** Persist the choice before any entity task is started or canceled. */
    public static void chooseEntityRecording(MinecraftServer server, long id, boolean record) {
        var run = runtime(server); run.data.requireWritable();
        var entry = run.data.entries.get(id);
        if (entry == null || !Set.of("PROTECTED", "EDITING").contains(entry.state))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.entity_choice_not_ready"));
        var choice = record ? MapResetData.EntityRecording.RECORD : MapResetData.EntityRecording.IGNORE;
        if (entry.entityRecording == choice) return;
        requireNoBusyOverlap(server, entry.map);
        // A completed replay retains crash-recovery originals. Flush their world state
        // before a new baseline generation can retire any entity ownership records.
        server.saveEverything(true, true, true);
        var replacement = entry.copy(); replacement.entityRecording = choice;
        replacement.failure = ""; replacement.entityCapture = record && !entry.state.equals("EDITING");
        replacement.entityReplay = false; replacement.entityRestoreSkipped = false;
        replacement.entityRound = null;
        if (record) {
            replacement.identity = UUID.randomUUID();
            replacement.entities.clear(); replacement.ownedEntities.clear();
        }
        replacement.state = entry.state.equals("EDITING") ? "EDITING" : record ? "CAPTURING" : "PROTECTED";
        replacePersisted(server, run, id, entry, replacement);
        EntityBaselineService.remove(server, id);
        closeProtectedMenus(server);
    }
    /** Confirmed abandonment keeps the original journal and only suppresses selected replay work. */
    public static void skipRestoration(MinecraftServer server, long id, boolean entities, boolean terrain) {
        var run = runtime(server); run.data.requireWritable();
        var entry = run.data.entries.get(id);
        if (entry == null || !entry.canSkipRecovery(entities, terrain))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.no_recovery_to_skip"));
        var replacement = entry.copy();
        replacement.skipRecovery(entities, terrain);
        replacePersisted(server, run, id, entry, replacement);
        var restore = run.restores.remove(id); if (restore != null) restore.close();
        EntityBaselineService.remove(server, id);
        // Accepted terrain is now the protected container baseline, including inventory-only edits.
        if (terrain) refreshContainers(server, replacement.map);
        closeProtectedMenus(server);
    }
    private static void replacePersisted(MinecraftServer server, Runtime run, long id,
            MapResetData.Entry original, MapResetData.Entry replacement) {
        run.data.entries.put(id, replacement);
        try { run.data.persist(server); }
        catch (RuntimeException error) {
            run.data.entries.put(id, original); run.data.setDirty(); throw error;
        }
    }
    public static boolean starting(MinecraftServer server, long id) {
        var run = RUNS.get(server); var e = run == null ? null : run.data.entries.get(id);
        return e != null && e.state.equals("PREPARED");
    }
    public static void beforeStart(MinecraftServer server, MapDefinition map, Runnable ready, Consumer<String> failed) {
        var run = runtime(server); run.data.requireWritable(); requireNoActiveOverlap(server, map);
        var e = run.data.entries.get(map.id());
        if (e == null || !MapResetData.sameRange(e.map, map)) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
        e.prepare(); run.data.setDirty();
        try {
            com.example.examplemod.lootspawn.LootRespawnService.prepareMap(server, map);
            com.example.examplemod.lootspawn.RoundLootService.begin(server, map);
            run.launches.put(map.id(), new Launch(ready, failed));
        } catch (RuntimeException error) {
            com.example.examplemod.lootspawn.RoundLootService.end(server, map);
            e.end(); run.data.setDirty(); throw error;
        }
    }
    public static void requirePrepared(MinecraftServer server, MapDefinition map) {
        var e = runtime(server).data.entries.get(map.id());
        if (e == null || !e.state.equals("PREPARED") || !MapResetData.sameRange(e.map, map))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
    }
    public static void started(MinecraftServer server, long id) {
        var run = runtime(server); var e = run.data.entries.get(id);
        if (e == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
        e.activate(); EntityBaselineService.cancelPreparation(server, id); run.data.setDirty();
    }
    public static void ended(MinecraftServer server, MapDefinition map) {
        var run = runtime(server); run.data.requireWritable();
        com.example.examplemod.lootspawn.RoundLootService.end(server, map);
        checkContainers(server); // Includes inventory-only edits, even when a mod omitted setChanged().
        var e = run.data.entries.get(map.id()); run.launches.remove(map.id());
        if (e == null) return;
        EntityBaselineService.cancelPreparation(server, map.id());
        e.end(); run.data.setDirty();
        closeProtectedMenus(server);
        announce(server, Texts.tr("message.tacz_pubg.journal.ended", map.id(), e.changes.size()));
    }
    public static void retry(MinecraftServer server, long id) {
        var run = runtime(server); run.data.requireWritable(); var e = run.data.entries.get(id);
        if (e == null || !e.state.equals("FAILED")) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.no_failed_task"));
        e.failure = ""; e.end(); run.data.setDirty();
    }
    private static boolean matches(MapResetData.Entry e, ServerLevel level, BlockPos pos) {
        return e.map.dimension().equals(level.dimension().location()) && MapResetData.contains(e.map, pos);
    }
    private static boolean managed(ServerLevel level, BlockPos pos) {
        var run = RUNS.get(level.getServer());
        return run != null && run.data.entries.values().stream().anyMatch(e -> matches(e, level, pos));
    }
    private static boolean writingAt(Runtime run, ServerLevel level, BlockPos pos) {
        return run.applying != null && run.applying.level == level && run.applying.pos == pos.asLong();
    }
    public static boolean protectedLiveEntity(BlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level) || !isLocked(level, entity.getBlockPos())) return false;
        var chunk = level.getChunkSource().getChunkNow(entity.getBlockPos().getX() >> 4, entity.getBlockPos().getZ() >> 4);
        return chunk != null && chunk.getBlockEntities().get(entity.getBlockPos()) == entity;
    }
    public static boolean containerLocked(net.minecraft.world.Container container) {
        if (container instanceof BlockEntity entity) return protectedLiveEntity(entity);
        if (container instanceof net.minecraft.world.CompoundContainer compound) {
            for (var levelChunks : CHUNKS.entrySet()) for (var chunk : levelChunks.getValue().values())
                for (var entity : chunk.getBlockEntities().values())
                    if (entity instanceof net.minecraft.world.Container part && compound.contains(part) && protectedLiveEntity(entity)) return true;
        }
        return false;
    }
    private static void closeProtectedMenus(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers())
            if (player.containerMenu != player.inventoryMenu && player.containerMenu.slots.stream().anyMatch(s -> containerLocked(s.container)))
                player.closeContainer();
    }
    public static boolean isLocked(ServerLevel level, BlockPos pos) {
        var run = RUNS.get(level.getServer()); if (run == null || writingAt(run, level, pos)) return false;
        if (!run.data.readable()) return MapZoneSavedData.get(level.getServer()).maps().stream()
                .anyMatch(m -> m.dimension().equals(level.dimension().location()) && MapResetData.contains(m, pos));
        return MapResetData.lockedAt(run.data.entries.values(), level.dimension().location(), pos);
    }
    public static boolean isRestoring(ServerLevel level, BlockPos pos) {
        var run = RUNS.get(level.getServer());
        return run != null && run.data.entries.values().stream().anyMatch(e -> matches(e, level, pos)
                && (e.state.equals("RESTORING") || e.state.equals("FAILED")));
    }
    /** Called before LevelChunk writes the old state or removes its block entity. */
    public static boolean beforeBlockChange(LevelChunk chunk, BlockPos pos, BlockState next) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return true;
        var run = RUNS.get(level.getServer()); if (run == null || writingAt(run, level, pos)) return true;
        if (isLocked(level, pos)) return false;
        if (chunk.getBlockState(pos) == next) return true;
        CompoundTag original = null;
        for (var e : run.data.entries.values()) if (e.state.equals("ACTIVE") && matches(e, level, pos) && !e.changes.containsKey(pos.asLong())) {
            if (original == null) {
                original = run.containers.get(new Position(level, pos.asLong()));
                if (original == null) original = snapshot(chunk, pos);
            }
            if (e.record(pos, original)) run.data.setDirty();
        }
        return true;
    }
    private static CompoundTag snapshot(LevelChunk chunk, BlockPos pos) {
        var tag = new CompoundTag(); tag.putLong("pos", pos.asLong());
        tag.put("state", NbtUtils.writeBlockState(chunk.getBlockState(pos)));
        // Do not create/remove block entities recursively while inside setBlockState/removeBlockEntity.
        var entity = chunk.getBlockEntities().get(pos);
        CompoundTag nbt = entity == null ? chunk.getBlockEntityNbt(pos) : entity.saveWithFullMetadata(chunk.getLevel().registryAccess());
        if (nbt != null) { nbt = nbt.copy(); nbt.remove("keepPacked"); tag.put("block_entity", nbt); }
        return tag;
    }
    private static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getChunk() instanceof LevelChunk chunk && chunk.getLevel() instanceof ServerLevel level) {
            CHUNKS.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>()).put(chunk.getPos().toLong(), chunk);
            captureContainers(chunk);
        }
    }
    private static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getChunk() instanceof LevelChunk chunk && chunk.getLevel() instanceof ServerLevel level) {
            for (var entity : List.copyOf(chunk.getBlockEntities().values())) blockEntityChanged(entity);
            var chunks = CHUNKS.get(level); if (chunks != null) chunks.remove(chunk.getPos().toLong());
            var run = RUNS.get(level.getServer());
            if (run != null) run.containers.keySet().removeIf(p -> p.level == level && new ChunkPos(BlockPos.of(p.pos)).equals(chunk.getPos()));
        }
    }
    private static void captureContainers(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        var run = RUNS.get(level.getServer()); if (run == null) return;
        for (var pos : List.copyOf(chunk.getBlockEntitiesPos())) if (managed(level, pos))
            run.containers.putIfAbsent(new Position(level, pos.asLong()), snapshot(chunk, pos));
    }
    private static void refreshContainers(MinecraftServer server, MapDefinition map) {
        var run = runtime(server);
        run.containers.keySet().removeIf(p -> map.dimension().equals(p.level.dimension().location())
                && MapResetData.contains(map, BlockPos.of(p.pos)));
        CHUNKS.forEach((level, chunks) -> {
            if (level.getServer() == server && map.dimension().equals(level.dimension().location()))
                chunks.values().forEach(MapResetService::captureContainers);
        });
    }
    /** Inventory edits mark themselves after mutation; use the copy captured on creation/chunk load. */
    public static void blockEntityChanged(BlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = entity.getBlockPos();
        var run = RUNS.get(level.getServer()); if (run == null || writingAt(run, level, pos)) return;
        var original = run.containers.get(new Position(level, pos.asLong()));
        if (original == null || !original.contains("block_entity")) return;
        var current = entity.saveWithFullMetadata(level.registryAccess());
        if (current.equals(original.getCompound("block_entity"))) return;
        if (isLocked(level, pos)) {
            // A different block entity may currently occupy a pending undo position.
            // Only the restore job may replace/load it; never feed chest NBT into a furnace.
            if (run.data.entries.values().stream().anyMatch(e -> matches(e, level, pos)
                    && (e.state.equals("RESTORING") || e.state.equals("FAILED")))) return;
            var previous = run.applying; run.applying = new Position(level, pos.asLong());
            try { entity.loadWithComponents(original.getCompound("block_entity").copy(), level.registryAccess()); entity.setChanged(); }
            finally { run.applying = previous; }
        } else for (var e : run.data.entries.values()) if (!e.locked() && matches(e, level, pos) && e.record(pos, original)) run.data.setDirty();
    }
    private static void checkContainers(MinecraftServer server) {
        var run = RUNS.get(server); if (run == null) return;
        for (var p : List.copyOf(run.containers.keySet())) {
            var pos = BlockPos.of(p.pos);
            var chunk = CHUNKS.getOrDefault(p.level, Map.of()).get(new ChunkPos(pos).toLong());
            if (chunk == null) continue;
            var entity = chunk.getBlockEntities().get(pos);
            if (entity != null) blockEntityChanged(entity);
        }
    }
    private static boolean overlap(MapDefinition a, MapDefinition b) {
        return a.dimension().equals(b.dimension()) && Math.min(a.maxX(), b.maxX()) > Math.max(a.minX(), b.minX())
                && Math.min(a.maxZ(), b.maxZ()) > Math.max(a.minZ(), b.minZ());
    }
    static void requireNoActiveOverlap(MinecraftServer server, MapDefinition map) {
        if (runtime(server).data.entries.values().stream().anyMatch(e -> e.map.id() != map.id()
                && !e.state.equals("PROTECTED") && overlap(e.map, map)))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.overlap"));
    }
    private static void requireNoBusyOverlap(MinecraftServer server, MapDefinition map) {
        if (runtime(server).data.entries.values().stream().anyMatch(e -> e.map.id() != map.id()
                && !e.state.equals("PROTECTED") && !e.state.equals("EDITING") && overlap(e.map, map)))
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.overlap"));
    }
    /** Runs on the server thread, including tickPaused in single-player; never requires window focus. */
    public static void tickMaintenance(MinecraftServer server) {
        var run = RUNS.get(server); if (run == null || !run.data.readable()) return;
        long deadline = System.nanoTime() + 8_000_000;
        EntityBaselineService.maintain(server, deadline);
        checkContainers(server);
        if (!server.isPaused()) for (var id : List.copyOf(run.launches.keySet())) {
            var launch = run.launches.get(id);
            try {
                var entry = run.data.entries.get(id);
                if (entry == null || !EntityBaselineService.prepareTick(server, entry, deadline)) continue;
                if (!com.example.examplemod.lootspawn.LootRespawnService.prepareMapTick(server, id)) continue;
                run.launches.remove(id); launch.ready.run();
            } catch (RuntimeException error) {
                run.launches.remove(id);
                var e = run.data.entries.get(id);
                EntityBaselineService.cancelPreparation(server, id);
                if (e != null) { com.example.examplemod.lootspawn.RoundLootService.end(server, e.map); e.end(); }
                run.data.setDirty(); launch.failed.accept(error.getMessage());
            }
        }
        for (var e : run.data.entries.values()) if (e.state.equals("CAPTURING") || e.state.equals("RESTORING")) {
            Restore restore = null;
            try {
                if (e.state.equals("CAPTURING")) {
                    if (EntityBaselineService.captureTick(server, e, deadline)) {
                        announce(server, Texts.tr("message.tacz_pubg.entities.captured", e.map.id(), e.entities.size()));
                        LOGGER.info("Map #{} entity baseline captured: {} entities", e.map.id(), e.entities.size());
                    }
                    if (System.nanoTime() >= deadline) break;
                    continue;
                }
                if (!EntityBaselineService.clearTick(server, e, deadline)) {
                    if (System.nanoTime() >= deadline) break;
                    continue;
                }
                restore = run.restores.computeIfAbsent(e.map.id(), ignored -> new Restore(server, e));
                boolean blocksDone = restore.tick(run, deadline);
                if (blocksDone) restore.bar.setVisible(false);
                if (blocksDone && EntityBaselineService.restoreTick(server, e, deadline)) {
                    e.state = "PROTECTED"; run.data.setDirty(); restore.close(); run.restores.remove(e.map.id());
                    LOGGER.info("Map #{} journal restored {} changed positions in {} chunks", e.map.id(), e.changes.size(), restore.chunkCount);
                    announce(server, Texts.tr("message.tacz_pubg.journal.complete", e.map.id(), e.changes.size(), restore.chunkCount));
                }
            } catch (RuntimeException error) {
                if (restore != null) restore.close(); run.restores.remove(e.map.id());
                EntityBaselineService.remove(server, e.map.id());
                e.state = "FAILED"; e.failure = String.valueOf(error.getMessage()); run.data.setDirty();
                LOGGER.error("Map #{} journal restore failed; protection and original records retained", e.map.id(), error);
                announce(server, Texts.tr("message.tacz_pubg.journal.failed", e.map.id(), e.failure));
            }
            if (System.nanoTime() >= deadline) break;
        }
    }
    private static final class Restore {
        final MinecraftServer server; final MapResetData.Entry entry; final ServerLevel level;
        final List<CompoundTag> records; final int chunkCount;
        final List<ChunkPos> chunks;
        final ServerBossEvent bar;
        private record Pending(CompletableFuture<?> future, long since) {}
        final Map<ChunkPos, Pending> loading = new LinkedHashMap<>();
        int cursor, nextChunk;
        Restore(MinecraftServer server, MapResetData.Entry entry) {
            this.server = server; this.entry = entry;
            level = server.getLevel(ResourceKey.create(Registries.DIMENSION, entry.map.dimension()));
            if (level == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.dimension_missing"));
            records = entry.terrainRestoreSkipped ? new ArrayList<>() : new ArrayList<>(entry.changes.values());
            records.sort(Comparator.comparingLong(tag -> new ChunkPos(BlockPos.of(tag.getLong("pos"))).toLong()));
            chunks = records.stream().map(t -> new ChunkPos(BlockPos.of(t.getLong("pos")))).distinct().toList();
            chunkCount = chunks.size();
            bar = new ServerBossEvent(Texts.of("message.tacz_pubg.journal.progress", entry.map.id(), 0, records.size(), chunkCount), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        }
        boolean tick(Runtime run, long deadline) {
            for (var player : server.getPlayerList().getPlayers()) if (player.hasPermissions(2) || player.serverLevel() == level) bar.addPlayer(player);
            int budget = 4096;
            while (cursor < records.size() && budget-- > 0 && System.nanoTime() < deadline) {
                var tag = records.get(cursor); var pos = BlockPos.of(tag.getLong("pos")); var cp = new ChunkPos(pos);
                while (loading.size() < 4 && nextChunk < chunks.size()) {
                    var ahead = chunks.get(nextChunk++);
                    level.getChunkSource().addRegionTicket(TICKET, ahead, 0, ahead);
                    // Its off-thread entry point schedules work safely on Minecraft's chunk pipeline.
                    var future = CompletableFuture.supplyAsync(() -> level.getChunkSource().getChunkFuture(ahead.x, ahead.z, ChunkStatus.FULL, true), LOADS).thenCompose(f -> f);
                    loading.put(ahead, new Pending(future, System.nanoTime()));
                }
                var pending = loading.get(cp); var future = pending.future;
                if (!future.isDone()) {
                    if (System.nanoTime() - pending.since > 60_000_000_000L) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.load_timeout", cp));
                    break;
                }
                future.join(); var chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
                if (chunk == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.load_timeout", cp));
                var previous = run.applying; run.applying = new Position(level, pos.asLong());
                try { restoreBlock(level, pos, tag); }
                finally { run.applying = previous; }
                var key = new Position(level, pos.asLong());
                if (tag.contains("block_entity")) run.containers.put(key, tag.copy()); else run.containers.remove(key);
                cursor++;
                if (cursor == records.size() || !new ChunkPos(BlockPos.of(records.get(cursor).getLong("pos"))).equals(cp)) {
                    level.getChunkSource().removeRegionTicket(TICKET, cp, 0, cp); loading.remove(cp);
                }
            }
            bar.setName(Texts.of("message.tacz_pubg.journal.progress", entry.map.id(), cursor, records.size(), chunkCount));
            bar.setProgress(records.isEmpty() ? 1 : (float) cursor / records.size());
            return cursor == records.size();
        }
        void close() {
            loading.forEach((cp, pending) -> { pending.future.cancel(false); level.getChunkSource().removeRegionTicket(TICKET, cp, 0, cp); });
            loading.clear(); bar.removeAllPlayers();
        }
    }
    private static void restoreBlock(ServerLevel level, BlockPos pos, CompoundTag original) {
        BlockState state = BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, original.getCompound("state")).getOrThrow();
        // Suppress neighbor cascades/drops; clients, heightmaps and lighting still receive the update.
        if (level.getBlockState(pos) != state) {
            // onRemove can drop a container's contents even with SUPPRESS_DROPS.
            // Remove its runtime entity first; the round's original NBT is already in the journal.
            level.removeBlockEntity(pos);
            level.setBlock(pos, state, 2 | 16 | 32);
        }
        if (level.getBlockState(pos) != state) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.write_failed", pos));
        if (original.contains("block_entity")) {
            var nbt = original.getCompound("block_entity").copy();
            var entity = level.getBlockEntity(pos);
            if (entity == null) {
                entity = BlockEntity.loadStatic(pos, state, nbt, level.registryAccess());
                if (entity == null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.write_failed", pos));
                level.setBlockEntity(entity);
            } else entity.loadWithComponents(nbt, level.registryAccess());
            entity.setChanged(); level.sendBlockUpdated(pos, state, state, 2);
        }
    }
    private static void announce(MinecraftServer server, String text) {
        server.getPlayerList().getPlayers().stream().filter(p -> p.hasPermissions(2)).forEach(p -> p.sendSystemMessage(Texts.component(text)));
    }
}
