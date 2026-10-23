package com.example.examplemod.backup;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

/** Opt-in, real server capture benchmark. Never opens a user's save. */
@GameTestHolder("entity_benchmark")
@PrefixGameTestTemplate(false)
public final class EntityBaselineBenchmark {
    private static final int SIDE = 512, CHUNKS = SIDE * SIDE, SECTOR = 4096;
    private static final int[] PERCENTAGES = {0, 25, 75, 100};
    private static final long LIMIT = 30_000_000_000L, INTERVAL = 50_000_000L;
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    @GameTest(template = "empty", templateNamespace = "entity_benchmark", timeoutTicks = Integer.MAX_VALUE,
            batch = "entity_baseline_benchmark")
    public static void capture8192SquareAtFourDensities(GameTestHelper helper) {
        if (!Boolean.getBoolean("tacz_pubg.entityBenchmark")) { helper.succeed(); return; }
        new Run(helper).start();
    }

    private record Fixture(int percent, int origin, int chunks, int entityChunks, int entities,
                           double generationSeconds, double verificationSeconds) {}

    private static final class Run {
        final GameTestHelper helper;
        final MinecraftServer server;
        final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "entity-benchmark-fixtures"); thread.setDaemon(true); return thread;
        });
        final long overallStart = System.nanoTime();
        final List<String> results = new ArrayList<>();
        CompletableFuture<Fixture> preparing;
        MapResetData data;
        MapResetData.Entry entry;
        Fixture fixture;
        int stage;
        long started, nextSlice, lastLog;
        int calls;
        double longestSliceMs;
        boolean finished;
        final boolean oneWorkload;

        Run(GameTestHelper helper) {
            this.helper = helper; server = helper.getLevel().getServer();
            int requested = Integer.getInteger("tacz_pubg.entityBenchmark.percent", -1);
            oneWorkload = requested >= 0;
            if (!oneWorkload) requested = Integer.getInteger("tacz_pubg.entityBenchmark.startPercent", -1);
            if (requested >= 0) {
                while (stage < PERCENTAGES.length && PERCENTAGES[stage] != requested) stage++;
                require(stage < PERCENTAGES.length, "Unsupported benchmark percentage");
            }
            Path output = outputPath();
            if (Files.exists(output)) try {
                for (var line : Files.readAllLines(output)) if (line.startsWith("{"))
                    results.add(line.endsWith(",") ? line.substring(0, line.length() - 1) : line);
            } catch (IOException error) { throw new UncheckedIOException(error); }
        }

        void start() {
            // The namespace is opt-in and this additionally refuses ordinary worlds.
            if (!server.getClass().getName().contains("GameTestServer"))
                throw new AssertionError("Entity benchmark requires an isolated GameTestServer");
            helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            prepareNext();
            helper.runAfterDelay(1, this::tick);
        }

        void prepareNext() {
            int percent = PERCENTAGES[stage], origin = 4096 + stage * 1024;
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            preparing = CompletableFuture.supplyAsync(() -> {
                try { return createAndVerify(root, percent, origin); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            }, writer);
            LOGGER.info("ENTITY_BENCH fixture_begin percent={} map_chunks={} origin={} setup_excluded=true",
                    percent, CHUNKS, origin);
        }

        void tick() {
            if (finished) return;
            try {
                if (System.nanoTime() - overallStart > 420_000_000_000L)
                    throw new AssertionError("Entity benchmark exceeded its seven-minute internal watchdog");
                if (entry == null) {
                    if (!preparing.isDone()) { helper.runAfterDelay(1, this::tick); return; }
                    fixture = preparing.join();
                    data = new MapResetData();
                    var map = new MapDefinition(98_000_000 + stage, "8192 entity benchmark " + fixture.percent,
                            helper.getLevel().dimension().location(), fixture.origin * 16.0, fixture.origin * 16.0,
                            (fixture.origin + SIDE) * 16.0 - 1e-6, (fixture.origin + SIDE) * 16.0 - 1e-6,
                            null, 1, List.of());
                    entry = new MapResetData.Entry(map); entry.state = "CAPTURING"; entry.entityCapture = true;
                    data.entries.put(map.id(), entry);
                    EntityBaselineService.initialize(server, data);
                    started = nextSlice = lastLog = System.nanoTime(); calls = 0; longestSliceMs = 0;
                    LOGGER.info("ENTITY_BENCH capture_begin percent={} expected_entities={} normal_maintenance_hz=20 deadline_ms=8",
                            fixture.percent, fixture.entities);
                }
                long now = System.nanoTime();
                if (now >= nextSlice) {
                    // GameTestServer runs unthrottled. Match production's once-per-50ms maintenance.
                    nextSlice = now + INTERVAL;
                    long before = System.nanoTime(), deadline = before + 8_000_000L;
                    EntityBaselineService.maintain(server, deadline);
                    boolean done = EntityBaselineService.captureTick(server, entry, deadline);
                    longestSliceMs = Math.max(longestSliceMs, (System.nanoTime() - before) / 1e6);
                    calls++;
                    if (done || System.nanoTime() - started >= LIMIT) { finishWorkload(done); return; }
                    if (now - lastLog >= 5_000_000_000L) {
                        LOGGER.info("ENTITY_BENCH progress percent={} captured={} elapsed_seconds={} diagnostics={}",
                                fixture.percent, entry.entities.size(), (now - started) / 1e9, describeSweep(server, entry.map.id()));
                        lastLog = now;
                    }
                }
                helper.runAfterDelay(1, this::tick);
            } catch (Throwable error) {
                if (entry != null) LOGGER.error("ENTITY_BENCH final_diagnostics {}", describeSweep(server, entry.map.id()));
                finished = true; EntityBaselineService.close(server); writer.shutdownNow();
                LOGGER.error("ENTITY_BENCH failed", error);
                helper.fail("Entity benchmark failed: " + error);
            }
        }

        void finishWorkload(boolean done) throws Exception {
            double seconds = (System.nanoTime() - started) / 1e9;
            long completed = done ? fixture.entityChunks : sweepCompleted(server, entry.map.id());
            int captured = entry.entities.size();
            if (captured > fixture.entities || completed > fixture.entityChunks)
                throw new AssertionError("Captured or visited more than the independently verified fixture");
            for (var record : entry.entities.values()) {
                var tag = record.getCompound("nbt");
                if (!tag.getString("id").equals("minecraft:armor_stand") || tag.contains("Passengers"))
                    throw new AssertionError("Unexpected captured entity type/tree");
            }
            if (done && captured != fixture.entities)
                throw new AssertionError("Completed capture lost entities: " + captured + " != " + fixture.entities);
            if (!done && captured == 0)
                throw new AssertionError("Thirty-second sample captured no real entities");
            double estimate = done ? seconds : seconds * fixture.entities / captured;
            String result = String.format(Locale.ROOT,
                    "{\"percent\":%d,\"map_blocks_x\":8192,\"map_blocks_z\":8192,\"map_chunks\":%d,"
                            + "\"fixture_entity_chunks\":%d,\"fixture_entities\":%d,\"fixture_counts_asserted\":true,"
                            + "\"generation_seconds\":%.3f,\"verification_seconds\":%.3f,\"measured_seconds\":%.3f,"
                            + "\"completed\":%s,\"completed_candidate_chunks\":%d,\"captured_entities\":%d,"
                            + "\"estimated_total_seconds\":%.3f,\"maintenance_calls\":%d,\"longest_slice_ms\":%.3f,"
                            + "\"actual_production_capture\":true,\"fixture\":\"saved_full_air_chunks_and_one_marker_armor_stand_per_selected_chunk\"}",
                    fixture.percent, fixture.chunks, fixture.entityChunks, fixture.entities,
                    fixture.generationSeconds, fixture.verificationSeconds, seconds, done,
                    completed, captured, estimate, calls, longestSliceMs);
            results.add(result); LOGGER.info("ENTITY_BENCH result {}", result);
            EntityBaselineService.close(server);
            // Keep loaded fixtures from accumulating AI/tick work or affecting the next sample.
            var loaded = new ArrayList<net.minecraft.world.entity.Entity>();
            helper.getLevel().getAllEntities().forEach(entity -> { if (entity != null) loaded.add(entity); });
            for (var entity : loaded)
                if (entity.getUUID().getMostSignificantBits() == 0x45_4e_54_42_45_4e_43_48L)
                    entity.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            entry = null; data = null;
            Path output = outputPath();
            Files.createDirectories(output.getParent());
            Files.writeString(output, "[\n" + String.join(",\n", results) + "\n]\n");
            if (oneWorkload || ++stage == PERCENTAGES.length) {
                finished = true; writer.shutdownNow();
                LOGGER.info("ENTITY_BENCH complete workloads=4 elapsed_seconds={} output={}",
                        (System.nanoTime() - overallStart) / 1e9, output);
                helper.succeed();
            } else {
                prepareNext(); helper.runAfterDelay(1, this::tick);
            }
        }
    }

    private static Path outputPath() {
        return Path.of(System.getProperty("tacz_pubg.entityBenchmark.output", "entity-baseline-benchmark-results.json")).toAbsolutePath();
    }

    private static String describeSweep(MinecraftServer server, long map) {
        try {
            Object state = ((Map<?, ?>) field(EntityBaselineService.class, "STATES").get(null)).get(server);
            Object task = ((Map<?, ?>) field(state.getClass(), "tasks").get(state)).get(map);
            Object sweep = field(task.getClass(), "sweep").get(task);
            var index = (CompletableFuture<?>) field(sweep.getClass(), "diskIndex").get(sweep);
            Object loads = field(sweep.getClass(), "loads").get(sweep);
            var pending = (Map<?, ?>) field(loads.getClass(), "pending").get(loads);
            var jobs = new ArrayList<String>();
            for (var item : pending.entrySet()) {
                Object value = item.getValue();
                var future = (CompletableFuture<?>) field(value.getClass(), "future").get(value);
                jobs.add(item.getKey() + ":futureDone=" + future.isDone() + ":exception=" + future.isCompletedExceptionally());
            }
            var pool = (ExecutorService) field(EntityBaselineService.class, "LOADS").get(null);
            return "indexDone=" + index.isDone() + ",indexException=" + index.isCompletedExceptionally()
                    + ",placesInitialized=" + (field(sweep.getClass(), "places").get(sweep) != null)
                    + ",total=" + field(sweep.getClass(), "total").getLong(sweep)
                    + ",completed=" + field(sweep.getClass(), "completed").getLong(sweep)
                    + ",pending=" + jobs + ",executor=" + pool;
        } catch (Throwable error) { return "diagnosticFailure=" + error; }
    }

    private static long sweepCompleted(MinecraftServer server, long map) throws Exception {
        // Test-only observation of existing production progress, without adding runtime hooks.
        Object state = ((Map<?, ?>) field(EntityBaselineService.class, "STATES").get(null)).get(server);
        Object task = ((Map<?, ?>) field(state.getClass(), "tasks").get(state)).get(map);
        Object sweep = field(task.getClass(), "sweep").get(task);
        return field(sweep.getClass(), "completed").getLong(sweep);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static Fixture createAndVerify(Path root, int percent, int origin) throws IOException {
        long start = System.nanoTime();
        Path terrain = root.resolve("region"), entities = root.resolve("entities");
        Files.createDirectories(terrain); Files.createDirectories(entities);
        int version = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int expected = CHUNKS * percent / 100;
        boolean reuse = Files.exists(terrain.resolve("r." + origin / 32 + "." + origin / 32 + ".mca"));
        if (!reuse) try (var codec = new RecordCodec()) {
            for (int rz = 0; rz < SIDE / 32; rz++) for (int rx = 0; rx < SIDE / 32; rx++) {
                int gx = origin / 32 + rx, gz = origin / 32 + rz;
                var chunkFile = ByteBuffer.allocate((1024 + 2) * SECTOR);
                var entityFile = ByteBuffer.allocate((1024 + 2) * SECTOR);
                int entitySector = 2;
                for (int index = 0; index < 1024; index++) {
                    int x = gx * 32 + index % 32, z = gz * 32 + index / 32;
                    var chunk = new CompoundTag(); chunk.putInt("DataVersion", version);
                    chunk.putInt("xPos", x); chunk.putInt("zPos", z); chunk.putInt("yPos", -4);
                    chunk.putString("Status", "minecraft:full"); chunk.putBoolean("isLightOn", true);
                    chunk.put("sections", new ListTag());
                    var heights = new CompoundTag();
                    for (var key : List.of("MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR", "WORLD_SURFACE"))
                        heights.putLongArray(key, new long[37]);
                    chunk.put("Heightmaps", heights);
                    putRecord(chunkFile, index, index + 2, codec.encode(chunk));
                    if (selected(x - origin, z - origin, percent)) {
                        var entity = new CompoundTag(); entity.putString("id", "minecraft:armor_stand");
                        entity.putUUID("UUID", uuid(x, z));
                        var pos = new ListTag(); pos.add(DoubleTag.valueOf(x * 16.0 + 8));
                        pos.add(DoubleTag.valueOf(80)); pos.add(DoubleTag.valueOf(z * 16.0 + 8));
                        entity.put("Pos", pos); entity.putBoolean("NoGravity", true);
                        entity.putBoolean("Marker", true); entity.putBoolean("Invisible", true);
                        var list = new ListTag(); list.add(entity);
                        var record = new CompoundTag(); record.putInt("DataVersion", version);
                        record.putIntArray("Position", new int[]{x, z}); record.put("Entities", list);
                        putRecord(entityFile, index, entitySector++, codec.encode(record));
                    }
                }
                Path chunkPath = terrain.resolve("r." + gx + "." + gz + ".mca");
                Path entityPath = entities.resolve("r." + gx + "." + gz + ".mca");
                if (Files.exists(chunkPath) || Files.exists(entityPath))
                    throw new IOException("Refusing to overwrite an existing benchmark region: " + chunkPath);
                Files.write(chunkPath, chunkFile.array(), StandardOpenOption.CREATE_NEW);
                if (entitySector > 2) Files.write(entityPath,
                        Arrays.copyOf(entityFile.array(), entitySector * SECTOR), StandardOpenOption.CREATE_NEW);
            }
        }
        double generation = (System.nanoTime() - start) / 1e9;
        long verify = System.nanoTime(); int chunkCount = 0, entityChunks = 0, entityCount = 0;
        for (int rz = 0; rz < SIDE / 32; rz++) for (int rx = 0; rx < SIDE / 32; rx++) {
            int gx = origin / 32 + rx, gz = origin / 32 + rz;
            byte[] chunks = Files.readAllBytes(terrain.resolve("r." + gx + "." + gz + ".mca"));
            Path entityPath = entities.resolve("r." + gx + "." + gz + ".mca");
            byte[] entitiesBytes = Files.exists(entityPath) ? Files.readAllBytes(entityPath) : null;
            for (int index = 0; index < 1024; index++) {
                int x = gx * 32 + index % 32, z = gz * 32 + index / 32;
                CompoundTag chunk = readRecord(chunks, index);
                require(chunk != null && chunk.getInt("xPos") == x && chunk.getInt("zPos") == z
                        && chunk.getString("Status").equals("minecraft:full"), "Full terrain chunk mismatch");
                chunkCount++;
                var record = entitiesBytes == null ? null : readRecord(entitiesBytes, index);
                boolean present = selected(x - origin, z - origin, percent);
                require((record != null) == present, "Entity occupancy mismatch");
                if (record == null) continue;
                var list = record.getList("Entities", Tag.TAG_COMPOUND);
                require(list.size() == 1, "A selected chunk must contain exactly one entity");
                var entity = list.getCompound(0); var pos = entity.getList("Pos", Tag.TAG_DOUBLE);
                require(entity.getString("id").equals("minecraft:armor_stand") && entity.getUUID("UUID").equals(uuid(x, z))
                        && pos.size() == 3 && pos.getDouble(0) == x * 16.0 + 8 && pos.getDouble(2) == z * 16.0 + 8,
                        "Entity identity/position mismatch");
                entityChunks++; entityCount += list.size();
            }
        }
        require(chunkCount == CHUNKS && entityChunks == expected && entityCount == expected, "Fixture total mismatch");
        double verification = (System.nanoTime() - verify) / 1e9;
        LOGGER.info("ENTITY_BENCH fixture_asserted percent={} chunks={} entity_chunks={} entities={} generation_seconds={} verification_seconds={}",
                percent, chunkCount, entityChunks, entityCount, generation, verification);
        return new Fixture(percent, origin, chunkCount, entityChunks, entityCount, generation, verification);
    }

    private static boolean selected(int x, int z, int percent) {
        int slot = (x + z * SIDE) & 3;
        return percent == 100 || percent == 75 && slot != 3 || percent == 25 && slot == 0;
    }
    private static UUID uuid(int x, int z) { return new UUID(0x45_4e_54_42_45_4e_43_48L, ChunkPos.asLong(x, z)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void putRecord(ByteBuffer file, int index, int sector, byte[] bytes) {
        require(bytes.length + 5 <= SECTOR, "Fixture record needs more than one sector");
        file.putInt(index * 4, sector << 8 | 1);
        file.position(sector * SECTOR); file.putInt(bytes.length + 1); file.put((byte) 2); file.put(bytes);
    }
    private static CompoundTag readRecord(byte[] file, int index) throws IOException {
        var buffer = ByteBuffer.wrap(file); int location = buffer.getInt(index * 4);
        if (location == 0) return null;
        int position = (location >>> 8) * SECTOR, length = buffer.getInt(position);
        require((location & 255) == 1 && length > 1 && length <= SECTOR - 4 && file[position + 4] == 2,
                "Invalid generated region record");
        try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(file, position + 5, length - 1)))) {
            return NbtIo.read(input, NbtAccounter.create(1_048_576));
        }
    }

    private static final class RecordCodec implements AutoCloseable {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        final ByteArrayOutputStream raw = new ByteArrayOutputStream(2048);
        final DataOutputStream data = new DataOutputStream(raw);
        final byte[] compressed = new byte[4096];
        byte[] encode(CompoundTag tag) throws IOException {
            raw.reset(); NbtIo.write(tag, data); deflater.reset(); deflater.setInput(raw.toByteArray()); deflater.finish();
            int count = deflater.deflate(compressed);
            require(deflater.finished(), "Fixture compression overflow");
            return Arrays.copyOf(compressed, count);
        }
        @Override public void close() { deflater.end(); }
    }
}
