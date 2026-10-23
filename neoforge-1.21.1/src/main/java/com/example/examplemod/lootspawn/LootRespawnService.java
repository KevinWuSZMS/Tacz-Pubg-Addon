package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.gunbalance.GunBalanceService;
import com.example.examplemod.lootconfig.AttachmentLootPolicy;
import com.example.examplemod.lootconfig.LootCatalog;
import com.example.examplemod.lootconfig.LootCategory;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.lootconfig.LootSpawnRules;
import com.example.examplemod.lootconfig.LootSpawnRules.FloorSample;
import com.example.examplemod.lootconfig.LootSpawnRules.Plane;
import com.example.examplemod.lootconfig.LootSpawnRules.PlannedItem;
import com.example.examplemod.lootspawn.LootRespawnSavedData.Job;
import com.example.examplemod.spawn.SpawnAreaSavedData;
import com.example.examplemod.spawn.SpawnFloorGeometry;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Server-authoritative global refresh; never force-loads terrain or entity chunks. */
public final class LootRespawnService {
    public static final int MAX_ENTITIES_PER_REGION = 512;
    private static final int MAX_JOBS_CHECKED_PER_TICK = 32;
    private static final int MAX_CHUNKS_CHECKED_PER_TICK = 128;
    private static final int MAX_FLOORS_SCANNED_PER_TICK = 512;
    private static final int MAX_LOGGED_COMPANION_FAILURES_PER_REGION = 3;
    private static final Map<MinecraftServer, Runner> RUNNERS = new HashMap<>();
    private static final Map<MinecraftServer, ConcurrentLinkedQueue<ChunkSignal>> SIGNALS = new ConcurrentHashMap<>();

    private static final net.minecraft.server.level.TicketType<ChunkPos> MAP_TICKET =
            net.minecraft.server.level.TicketType.create("tacz_pubg_map_loot", java.util.Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<MinecraftServer, Map<Long, MapRefresh>> MAP_REFRESHES = new HashMap<>();
    private static final class MapRefresh {
        final LootRespawnSavedData saved;
        final Runner runner;
        final ServerLevel level;
        final Map<Long, java.util.concurrent.CompletableFuture<?>> tickets = new HashMap<>();
        int ticks;
        MapRefresh(LootRespawnSavedData saved, ServerLevel level) { this.saved = saved; runner = new Runner(saved); this.level = level; }
        void release() {
            tickets.keySet().forEach(c -> { var p = new ChunkPos(c); level.getChunkSource().removeRegionTicket(MAP_TICKET, p, 2, p); });
            tickets.clear();
        }
    }
    public static void prepareMap(MinecraftServer server, com.example.examplemod.zones.MapZoneModel.MapDefinition map) {
        cancelMap(server, map);
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, map.dimension()));
        if (level == null) throw new IllegalStateException("Map dimension is unavailable");
        GunBalanceService.requireSettingsReady(server);
        var document = LootConfigStore.current(level);
        var errors = LootConfigModel.validate(document);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        var jobs = new ArrayList<Job>();
        for (var region : SpawnAreaSavedData.get(server).regions()) {
            var floors = region.floors().stream().filter(p -> com.example.examplemod.spawn.MapSpawnBounds.contains(map, region.dimension(), p)).toList();
            if (!floors.isEmpty()) jobs.add(new Job(region.id(), region.dimension(), floors,
                    level.random.nextLong(), "", region.rarity(), region.tier()));
        }
        var saved = new LootRespawnSavedData(); saved.begin(document, jobs, null);
        MAP_REFRESHES.computeIfAbsent(server, ignored -> new HashMap<>()).put(map.id(), new MapRefresh(saved, level));
    }
    /** Prepare one region at a time, requesting at most two missing chunks per tick without blocking. */
    public static boolean prepareMapTick(MinecraftServer server, long mapId) {
        var tasks = MAP_REFRESHES.get(server);
        var refresh = tasks == null ? null : tasks.get(mapId);
        if (refresh == null) throw new IllegalStateException("Map loot preparation was cancelled");
        if (!GunBalanceService.isSettingsReady(server)) return false;
        var runner = refresh.runner; var saved = refresh.saved;
        if (++refresh.ticks > 20 * 300) {
            for (Job job : saved.jobs()) if (!job.failed()) saved.failed(job.id(), "Loot preparation timed out; region skipped");
            return finishMapRefresh(tasks, mapId, refresh);
        }
        if (runner.active != null) {
            scanAndCommit(server, saved, runner);
            if (runner.active == null) refresh.release();
            return false;
        }
        if (runner.waiting.isEmpty()) {
            return finishMapRefresh(tasks, mapId, refresh);
        }
        long id = runner.waiting.getFirst(); var job = saved.job(id);
        if (job == null || job.failed()) { runner.waiting.removeFirst(); return false; }
        int requested = 0;
        boolean ready = true;
        for (long packed : runner.required.get(id)) {
            var chunk = new ChunkPos(packed);
            if (!refresh.tickets.containsKey(packed) && requested < 2) {
                refresh.level.getChunkSource().addRegionTicket(MAP_TICKET, chunk, 2, chunk);
                refresh.tickets.put(packed, refresh.level.getChunkSource().getChunkFuture(
                        chunk.x, chunk.z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
                requested++;
            }
            var future = refresh.tickets.get(packed);
            if (future != null && future.isCompletedExceptionally()) {
                fail(saved, job, new IllegalStateException("Cannot load loot region chunk " + chunk));
                runner.waiting.removeFirst();
                refresh.release();
                return false;
            }
            if (future == null || refresh.level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null
                    || !refresh.level.areEntitiesLoaded(packed)) ready = false;
        }
        if (ready) { runner.waiting.removeFirst(); runner.active = new Active(job, refresh.level); }
        return false;
    }
    private static boolean finishMapRefresh(Map<Long, MapRefresh> tasks, long mapId, MapRefresh refresh) {
        refresh.release();
        tasks.remove(mapId);
        ExampleMod.LOGGER.info("Map #{} loot refreshed: {} regions, {} piles, {} skipped regions",
                mapId, refresh.saved.completedRegions(), refresh.saved.spawnedEntities(), refresh.saved.failedCount());
        return true;
    }
    public static void cancelMap(MinecraftServer server, com.example.examplemod.zones.MapZoneModel.MapDefinition map) {
        var tasks = MAP_REFRESHES.get(server);
        var refresh = tasks == null ? null : tasks.remove(map.id());
        if (refresh != null) refresh.release();
        LootRespawnSavedData.get(server).cancel(map);
    }
    private LootRespawnService() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(LootRespawnService::onChunkLoad);
        gameBus.addListener(LootRespawnService::onChunkUnload);
        gameBus.addListener(LootRespawnService::onServerTick);
        gameBus.addListener(LootRespawnService::onServerStopped);
    }

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(com.example.examplemod.commands.PubgCommandHelp.group("loot")
                .then(Commands.literal("respawn").requires(source -> source.hasPermission(2))
                .executes(context -> begin(context.getSource()))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Texts.component(getStatus(context.getSource().getServer())), false);
                    return 1;
                }))));
    }

    private static int begin(CommandSourceStack source) {
        if (!source.hasPermission(2)) return 0;
        MinecraftServer server = source.getServer();
        try {
            GunBalanceService.requireSettingsReady(server);
            Document document = LootConfigStore.current(source.getLevel());
            List<String> errors = LootConfigModel.validate(document);
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
            SpawnAreaSavedData areas = SpawnAreaSavedData.get(server);
            List<Job> jobs = new ArrayList<>();
            Random random = new Random(server.overworld().getRandom().nextLong());
            for (SpawnAreaSavedData.Region region : areas.regions()) {
                var maps = com.example.examplemod.zones.MapZoneSavedData.get(server).maps();
                var floors = region.floors().stream().filter(p -> maps.stream().anyMatch(m ->
                        com.example.examplemod.spawn.MapSpawnBounds.contains(m, region.dimension(), p))).toList();
                if (!floors.isEmpty()) jobs.add(new Job(region.id(), region.dimension(), floors, random.nextLong(), "", region.rarity(), region.tier()));
            }
            if (jobs.isEmpty()) {
                source.sendFailure(Texts.of("message.tacz_pubg.loot_respawn_service.no_confirmed_loot_regions_exist_world_existing_loot"));
                return 0;
            }
            UUID requester = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
            LootRespawnSavedData saved = LootRespawnSavedData.get(server);
            saved.begin(document, jobs, requester);
            String message = Texts.tr("message.tacz_pubg.loot_respawn_service.submitted_world_wide_loot_refresh_dimensions_regions_loaded", saved.epoch(), areas.dimensions().size(), jobs.size());
            source.sendSuccess(() -> Texts.component(message), false);
            return 1;
        } catch (RuntimeException error) {
            ExampleMod.LOGGER.warn("Cannot schedule global loot refresh", error);
            source.sendFailure(Texts.of("message.tacz_pubg.loot_respawn_service.loot_refresh_was_not_submitted_existing_loot_retained", message(error)));
            return 0;
        }
    }

    public static String getStatus(MinecraftServer server) {
        LootRespawnSavedData saved = LootRespawnSavedData.get(server);
        if (saved.epoch() == 0) return Texts.tr("message.tacz_pubg.loot_respawn_service.no_world_wide_loot_refresh_has_been_run");
        String result = Texts.tr("message.tacz_pubg.loot_respawn_service.loot_refresh_regions_complete_awaiting_loading_processing_failed", saved.epoch(), saved.completedRegions(), saved.totalRegions(), saved.pendingCount(), saved.failedCount(), saved.spawnedEntities());
        if (!GunBalanceService.isSettingsReady(server)) result += Texts.tr("message.tacz_pubg.loot_respawn_service.gun_settings_still_being_applied_companion_ammo_deferred");
        Runner runner = RUNNERS.get(server);
        if (runner != null && runner.epoch == saved.epoch() && runner.active != null) {
            result += Texts.tr("message.tacz_pubg.loot_respawn_service.current_region_preflight_floors", runner.active.cursor, runner.active.job.floors().size());
        }
        if (!saved.snapshotError().isBlank()) result += " " + saved.snapshotError();
        for (Job job : saved.jobs()) {
            if (job.failed()) {
                result += Texts.tr("message.tacz_pubg.loot_respawn_service.first_failure_region_fix_run_itemrespawn_again", job.id(), job.dimension(), job.floors().getFirst().toShortString(), job.failure());
                break;
            }
        }
        return result;
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        signal(event, true);
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        signal(event, false);
    }

    private static void signal(ChunkEvent event, boolean loaded) {
        if (event.getLevel() instanceof ServerLevel level) {
            // ChunkEvent.Load can precede FULL promotion. No world reads or writes here.
            SIGNALS.computeIfAbsent(level.getServer(), ignored -> new ConcurrentLinkedQueue<>())
                    .add(new ChunkSignal(level.dimension().location(), event.getChunk().getPos().toLong(), loaded));
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        var refreshes = MAP_REFRESHES.remove(event.getServer());
        if (refreshes != null) refreshes.values().forEach(MapRefresh::release);
        RUNNERS.remove(event.getServer());
        SIGNALS.remove(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // Delayed regions use the applied native indices, not a partially reloaded supply catalog.
        if (!GunBalanceService.isSettingsReady(server)) return;
        LootRespawnSavedData saved = LootRespawnSavedData.get(server);
        Runner runner = RUNNERS.get(server);
        if (runner == null || runner.epoch != saved.epoch()) {
            runner = new Runner(saved);
            RUNNERS.put(server, runner);
        }
        ConcurrentLinkedQueue<ChunkSignal> signals = SIGNALS.get(server);
        if (signals != null) {
            ChunkSignal signal;
            while ((signal = signals.poll()) != null) {
                if (signal.loaded) continue;
                for (long id : runner.affected.getOrDefault(new DimChunk(signal.dimension, signal.chunk), List.of())) {
                    runner.checkers.remove(id);
                    if (runner.active != null && runner.active.job.id() == id) {
                        runner.waiting.addLast(id);
                        runner.active = null;
                    }
                }
            }
        }
        if (saved.document() == null || !saved.snapshotError().isBlank()) return;
        if (runner.active != null) {
            scanAndCommit(server, saved, runner);
            return;
        }
        int chunkBudget = MAX_CHUNKS_CHECKED_PER_TICK;
        for (int inspected = 0; inspected < MAX_JOBS_CHECKED_PER_TICK && !runner.waiting.isEmpty(); inspected++) {
            long id = runner.waiting.removeFirst();
            Job job = saved.job(id);
            if (job == null || job.failed()) continue;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, job.dimension()));
            if (level == null) {
                fail(saved, job, new IllegalStateException(Texts.tr("message.tacz_pubg.loot_respawn_service.dimension_not_currently_server_s_dimension_list")));
                continue;
            }
            Checker checker = runner.checkers.computeIfAbsent(id, ignored -> new Checker());
            List<Long> chunks = runner.required.get(id);
            while (checker.cursor < chunks.size() && chunkBudget > 0) {
                long chunk = chunks.get(checker.cursor);
                chunkBudget--;
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null
                        || !level.areEntitiesLoaded(chunk)) break;
                checker.cursor++;
            }
            if (checker.cursor == chunks.size()) {
                runner.checkers.remove(id);
                runner.active = new Active(job, level);
                scanAndCommit(server, saved, runner);
                return;
            }
            runner.waiting.addLast(id);
            if (chunkBudget == 0) break;
        }
        if (runner.waiting.isEmpty() && !saved.completionNotified()) {
            saved.notified();
            String status = getStatus(server);
            ExampleMod.LOGGER.info(status);
            if (saved.requester() != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(saved.requester());
                if (player != null) player.sendSystemMessage(Texts.component(status));
            }
        }
    }

    private static void scanAndCommit(MinecraftServer server, LootRespawnSavedData saved, Runner runner) {
        Active active = Objects.requireNonNull(runner.active);
        if (active.job.floors().stream().anyMatch(floor -> MapResetService.isRestoring(active.level, floor))) {
            return; // Ground loot may spawn during protection; wait only while its supporting terrain is restored.
        }
        try {
            SpawnAreaSavedData areas = SpawnAreaSavedData.get(server);
            int end = Math.min(active.job.floors().size(), active.cursor + MAX_FLOORS_SCANNED_PER_TICK);
            while (active.cursor < end) {
                BlockPos floor = active.job.floors().get(active.cursor++);
                if (!areas.contains(active.job.dimension(), floor) || !SpawnFloorGeometry.isValidFloor(active.level, floor)) continue;
                double top = SpawnFloorGeometry.topSurfaces(active.level, floor).stream()
                        .mapToDouble(SpawnFloorGeometry.Surface::y).max().orElseThrow();
                active.samples.add(new FloorSample(floor, floor.getY() + top));
            }
            if (active.cursor < active.job.floors().size()) return;
            int generated = commit(active, Objects.requireNonNull(saved.document()), saved.epoch());
            saved.completed(active.job.id(), generated);
        } catch (RuntimeException error) {
            fail(saved, active.job, error);
        } finally {
            if (active.cursor >= active.job.floors().size() || saved.job(active.job.id()) == null
                    || saved.job(active.job.id()).failed()) runner.active = null;
        }
    }

    private static int commit(Active active, Document document, long epoch) {
        document = document.forTier(active.job.tier());
        Random random = new Random(active.job.seed());
        // A large region can be scanned over several ticks. Do not use floors an author
        // removed while that scan was in progress, even if their geometry stayed intact.
        SpawnAreaSavedData currentAreas = SpawnAreaSavedData.get(active.level.getServer());
        List<FloorSample> approved = active.samples.stream()
                .filter(sample -> currentAreas.contains(active.job.dimension(), sample.floor())).toList();
        // Keep this refresh's authoring choices while evaluating compatibility against applied native assets.
        // The shared snapshot prevents re-scanning the full gun/attachment catalog for every gun below.
        AttachmentLootPolicy.GenerationSnapshot attachments = AttachmentLootPolicy.captureSnapshot(document, active.level);
        // Weight-policy copies must never become a source of authoring settings. Each job owns
        // this document snapshot, including per-gun supply choices even during later config edits.
        Map<String, LootConfigModel.Entry> authoredEntries = new HashMap<>();
        document.items().forEach(entry -> authoredEntries.put(entry.key(), entry));
        var gunClasses = LootCatalog.gunClasses();
        List<PlannedItem> plans = LootSpawnRules.plan(approved, attachments.effectiveDocument(), active.job.rarity(), random,
                entry -> {
                    AttachmentLootPolicy.Info info = attachments.inventory().info(entry.id());
                    return LootCategory.classify(entry, info == null ? "" : info.category());
                }, entry -> gunClasses.getOrDefault(entry.id(), com.example.examplemod.lootconfig.GunClass.OTHER),
                LootCatalog.gunClassCounts(gunClasses));
        Map<BlockPos, LootSpawnPlacement.FloorIndex> planeForFloor = new HashMap<>();
        for (Plane plane : LootSpawnRules.planes(approved, document.general().samePlaneYTolerance())) {
            LootSpawnPlacement.FloorIndex indexed = new LootSpawnPlacement.FloorIndex(plane.floors());
            for (FloorSample floor : plane.floors()) planeForFloor.put(floor.floor(), indexed);
        }
        Set<BlockPos> anchors = new HashSet<>(active.job.floors());
        List<GroundLootEntity> old = new ArrayList<>();
        for (var existing : active.level.getAllEntities()) {
            if (existing instanceof GroundLootEntity loot && loot.isRespawnManaged() && anchors.contains(loot.anchor()))
                old.add(loot);
        }
        SpawnBatch batch = new SpawnBatch(active, epoch);
        List<GunCompanion> companions = new ArrayList<>();
        // Primary items have priority. Each successful carrier owns its space; a failed
        // item never rolls back other items or leaves an invisible reservation.
        for (PlannedItem plan : plans) {
            if (batch.remaining() == 0) { batch.budgetReached = true; break; }
            var entry = plan.entry();
            try {
                LootConfigModel.Entry authored = Objects.requireNonNull(authoredEntries.get(entry.key()),
                        Texts.tr("message.tacz_pubg.loot_respawn_service.spawn_plan_missing_loot_configuration_snapshot"));
                ItemStack base = LootCatalog.createStack(entry.kind(), entry.id(), active.level);
                int total = (int) Math.min(plan.count(), (long) base.getMaxStackSize() * batch.remaining());
                if (total < plan.count()) batch.budgetReached = true;
                List<Integer> quantities = AmmoGrouping.split(total, base.getMaxStackSize(),
                        AmmoGrouping.RemainderPolicy.KEEP_REMAINDER, batch.remaining());
                LootSpawnPlacement.FloorIndex plane = planeForFloor.get(plan.anchor().floor());
                List<LootSpawnPlacement.Slot> slots = LootSpawnPlacement.reservePrimary(active.level, plan.anchor(),
                        plane, base, quantities.size(), random, new ArrayList<>(batch.occupied));
                batch.unplaced += quantities.size() - slots.size();
                for (int i = 0; i < slots.size(); i++) {
                    if (batch.add(base, quantities.get(i), slots.get(i)) && "gun".equals(entry.kind()))
                        companions.add(new GunCompanion(base.copyWithCount(1), authored, slots.get(i), plane));
                }
            } catch (RuntimeException error) {
                batch.failed(entry.id(), error);
            }
        }
        // Only guns actually added to the world receive companions. Missing ammunition,
        // an obstructed annulus or a full carrier budget simply produces fewer piles.
        for (GunCompanion gun : companions) {
            if (batch.remaining() == 0) { batch.budgetReached = true; break; }
            try {
                var settings = gun.entry().ammoSupply() == null
                        ? LootCatalog.gunAmmoDefaults(ResourceLocation.parse(gun.entry().id())) : gun.entry().ammoSupply();
                var supply = LootGunAmmoLinks.roll(gun.gun(), settings, LootCatalog::ammoForGun, ResourceLocation::parse,
                        id -> TimelessAPI.getCommonAmmoIndex(id).isPresent(), random);
                int count = Math.min(supply.groups(), batch.remaining());
                if (count == 0) continue;
                if (count < supply.groups()) batch.budgetReached = true;
                ItemStack ammo = LootCatalog.createStack("ammo", supply.ammoId().toString(), active.level);
                List<LootSpawnPlacement.Slot> slots = LootSpawnPlacement.reserveAmmo(active.level, gun.slot(), gun.plane(),
                        ammo, count, document.general().ammoRadiusMin(), document.general().ammoRadiusMax(),
                        random, new ArrayList<>(batch.occupied));
                batch.unplaced += count - slots.size();
                for (LootSpawnPlacement.Slot slot : slots) batch.add(ammo, supply.roundsPerGroup(), slot);
            } catch (RuntimeException error) {
                batch.failed("ammo for " + gun.entry().id(), error);
            }
        }
        spawnOptionalAttachments(active, document, attachments, companions, batch, random);
        // A completed refresh replaces old managed loot even if no new item could fit.
        // Never touches ItemEntity, another mod's entity or a different anchor set.
        for (GroundLootEntity entity : old) entity.discard();
        if (batch.unplaced > 0 || batch.failures > 0 || batch.budgetReached)
            ExampleMod.LOGGER.info("Loot region #{} generated {} piles; skipped {} piles without space and {} failed items; "
                            + "carrier budget reached: {}. Refresh continues.",
                    active.job.id(), batch.generated, batch.unplaced, batch.failures, batch.budgetReached);
        return batch.generated;
    }

    private static void spawnOptionalAttachments(Active active, Document document,
            AttachmentLootPolicy.GenerationSnapshot attachments, List<GunCompanion> guns, SpawnBatch batch, Random random) {
        if (document.general().companionAttachmentChance() == 0
                || document.general().companionAttachmentKinds() == 0 || guns.isEmpty()) return;
        for (GunCompanion gun : guns) {
            if (batch.remaining() == 0) { batch.budgetReached = true; return; }
            List<String> kinds;
            try {
                kinds = AttachmentCompanionSelection.select(attachments.specializedCompanionCandidates(gun.gun()),
                        document.general().companionAttachmentChance(), document.general().companionAttachmentKinds(), random);
            } catch (RuntimeException error) {
                batch.failed("attachment candidates for " + gun.entry().id(), error);
                continue;
            }
            for (String id : kinds) {
                if (batch.remaining() == 0) { batch.budgetReached = true; return; }
                try {
                    ItemStack attachment = LootCatalog.createStack("attachment", id, active.level);
                    List<LootSpawnPlacement.Slot> slots = LootSpawnPlacement.reserveAmmo(active.level, gun.slot(),
                            gun.plane(), attachment, 1, document.general().ammoRadiusMin(),
                            document.general().ammoRadiusMax(), random, new ArrayList<>(batch.occupied));
                    if (slots.isEmpty()) { batch.unplaced++; continue; }
                    batch.add(attachment, 1, slots.getFirst());
                } catch (RuntimeException error) {
                    batch.failed(id, error);
                }
            }
        }
    }

    private static final class SpawnBatch {
        final Active active;
        final long epoch;
        final List<AABB> occupied = new ArrayList<>();
        int generated, unplaced, failures;
        boolean budgetReached;
        SpawnBatch(Active active, long epoch) { this.active = active; this.epoch = epoch; }
        int remaining() { return MAX_ENTITIES_PER_REGION - generated; }
        boolean add(ItemStack base, int quantity, LootSpawnPlacement.Slot slot) {
            GroundLootEntity entity = null;
            try {
                entity = prepareEntity(base, quantity, slot, active.level, epoch);
                if (!active.level.addFreshEntity(entity)) throw new IllegalStateException("Server rejected ground loot carrier");
                occupied.add(slot.bounds().inflate(0.02, 0, 0.02));
                generated++;
                return true;
            } catch (RuntimeException error) {
                if (entity != null) {
                    try { entity.discard(); }
                    catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                }
                failed(base.toString(), error);
                return false;
            }
        }
        void failed(String item, RuntimeException error) {
            if (++failures <= MAX_LOGGED_COMPANION_FAILURES_PER_REGION)
                ExampleMod.LOGGER.warn("Loot item {} skipped in region #{}: {}; other items and match preparation continue",
                        item, active.job.id(), message(error));
        }
    }

    private static GroundLootEntity prepareEntity(ItemStack base, int quantity, LootSpawnPlacement.Slot slot,
            ServerLevel level, long epoch) {
        GroundLootEntity entity = Objects.requireNonNull(LootSpawnRegistration.GROUND_LOOT.get().create(level));
        entity.initialize(base, quantity, slot.floor(), epoch);
        entity.setPos(slot.x(), slot.y(), slot.z());
        entity.setYRot(slot.yaw());
        entity.yRotO = entity.getYRot();
        entity.getItem().save(level.registryAccess()); // Persistence preflight precedes deleting old loot.
        return entity;
    }

    private record GunCompanion(ItemStack gun, LootConfigModel.Entry entry,
            LootSpawnPlacement.Slot slot, LootSpawnPlacement.FloorIndex plane) {}

    private static AABB regionBounds(List<BlockPos> floors) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos floor : floors) {
            minX = Math.min(minX, floor.getX()); minY = Math.min(minY, floor.getY()); minZ = Math.min(minZ, floor.getZ());
            maxX = Math.max(maxX, floor.getX()); maxY = Math.max(maxY, floor.getY()); maxZ = Math.max(maxZ, floor.getZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1D, maxY + 2D, maxZ + 1D);
    }

    private static void fail(LootRespawnSavedData saved, Job job, RuntimeException error) {
        saved.failed(job.id(), message(error));
        ExampleMod.LOGGER.warn("Loot refresh failed in {} near {}; previous ground loot was retained where possible",
                job.dimension(), job.floors().getFirst(), error);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record DimChunk(ResourceLocation dimension, long chunk) {}
    private record ChunkSignal(ResourceLocation dimension, long chunk, boolean loaded) {}
    private static final class Checker { int cursor; }
    private static final class Active {
        final Job job;
        final ServerLevel level;
        final List<FloorSample> samples = new ArrayList<>();
        int cursor;
        Active(Job job, ServerLevel level) { this.job = job; this.level = level; }
    }
    private static final class Runner {
        final long epoch;
        final ArrayDeque<Long> waiting = new ArrayDeque<>();
        final Map<Long, List<Long>> required = new HashMap<>();
        final Map<DimChunk, List<Long>> affected = new HashMap<>();
        final Map<Long, Checker> checkers = new HashMap<>();
        Active active;
        Runner(LootRespawnSavedData saved) {
            epoch = saved.epoch();
            for (Job job : saved.jobs()) {
                if (job.failed()) continue;
                waiting.addLast(job.id());
                List<Long> chunks = job.floors().stream().map(floor -> ChunkPos.asLong(floor.getX() >> 4, floor.getZ() >> 4))
                        .distinct().sorted().toList();
                required.put(job.id(), chunks);
                for (long chunk : chunks) {
                    affected.computeIfAbsent(new DimChunk(job.dimension(), chunk), ignored -> new ArrayList<>()).add(job.id());
                }
            }
        }
    }
}
