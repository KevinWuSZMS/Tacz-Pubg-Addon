package com.example.examplemod.backup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual read-only Anvil fixtures; no world startup, entity registry or terrain loading. */
public final class EntityRegionCandidateChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("tacz-entity-candidates-");
        try {
            modernRecords(root.resolve("modern"));
            legacyRecords(root.resolve("legacy"));
            cancellation(root.resolve("cancel"));
            check(EntityRegionCandidates.collect(root.resolve("missing"),
                    new EntityRegionCandidates.Bounds(-100, -100, 100, 100), () -> false).isEmpty(),
                    "absent folders produce no candidates");
            System.out.println("EntityRegionCandidateChecks: " + checks
                    + " read-only disk index, legacy, compression, boundary and cancellation checks passed; no game launched.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void modernRecords(Path root) throws Exception {
        var folder = Files.createDirectories(root.resolve("entities"));
        var region = new RegionFixture(folder, -1, 0);
        region.add(-32, 0, modern(true), RegionFileVersion.VERSION_GZIP, false);
        region.add(-31, 0, modern(false), RegionFileVersion.VERSION_DEFLATE, false);
        region.add(-30, 0, modern(true), RegionFileVersion.VERSION_NONE, false);
        region.add(-29, 0, modern(true), RegionFileVersion.VERSION_LZ4, false);
        region.add(-28, 0, modern(true), RegionFileVersion.VERSION_DEFLATE, true);
        // A list below an unrelated compound must never be mistaken for root Entities.
        var nestedOnly = new CompoundTag(); nestedOnly.put("Unrelated", modern(true));
        region.add(-27, 0, nestedOnly, RegionFileVersion.VERSION_DEFLATE, false);
        region.write();
        // Invalid/out-of-bounds region data must not even be opened.
        Files.write(folder.resolve("r.1000.1000.mca"), new byte[]{1});
        Files.write(folder.resolve("r.2147483648.0.mca"), new byte[]{1});
        Files.write(folder.resolve("r.not-a-number.0.mca"), new byte[]{1});
        var before = snapshot(root);
        var found = EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(-32, 0, -27, 0), () -> false);
        check(found.equals(Set.of(pos(-32, 0), pos(-30, 0), pos(-29, 0), pos(-28, 0))),
                "modern index supports gzip, deflate, raw, LZ4 and external streams; empty lists stay unloaded");
        check(EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(-29, 0, -29, 0), () -> false)
                        .equals(Set.of(pos(-29, 0))),
                "negative region coordinates and inclusive single-chunk bounds are exact");
        check(EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(-28, 1, -28, 1), () -> false).isEmpty(),
                "empty chunk slots remain absent even within an occupied region");
        check(sameSnapshot(before, snapshot(root)), "indexing never writes, truncates or creates region files");
    }

    private static void legacyRecords(Path root) throws Exception {
        var folder = Files.createDirectories(root.resolve("region"));
        var region = new RegionFixture(folder, 0, -1);
        region.add(0, -32, legacy(true, 2586), RegionFileVersion.VERSION_DEFLATE, false);
        region.add(1, -32, legacy(false, 2586), RegionFileVersion.VERSION_DEFLATE, false);
        region.add(2, -32, legacy(true, -1), RegionFileVersion.VERSION_NONE, false);
        var modernTerrain = new CompoundTag(); modernTerrain.putInt("DataVersion", 3955);
        // List names in terrain below an unrelated compound are not entity records.
        modernTerrain.put("Unrelated", modern(true));
        region.add(3, -32, modernTerrain, RegionFileVersion.VERSION_DEFLATE, false);
        region.write();
        check(EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(0, -32, 31, -1), () -> false)
                        .equals(Set.of(pos(0, -32), pos(2, -32))),
                "legacy Level.Entities works with and without DataVersion while empty/current terrain stays excluded");
        // A current entity file and an old terrain record for one position form one candidate.
        var current = new RegionFixture(Files.createDirectories(root.resolve("entities")), 0, -1);
        current.add(0, -32, modern(true), RegionFileVersion.VERSION_DEFLATE, false); current.write();
        check(EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(0, -32, 31, -1), () -> false).size() == 2,
                "separate entity storage and legacy terrain candidates are deduplicated");
    }

    private static void cancellation(Path root) throws Exception {
        boolean cancelled = false;
        try {
            EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(0, 0, 0, 0), () -> true);
        } catch (CancellationException expected) { cancelled = true; }
        check(cancelled, "cancellation also works before absent directory enumeration");

        var region = new RegionFixture(Files.createDirectories(root.resolve("region")), 0, 0);
        // A skipped large compressed byte array must remain cancellable within this record.
        var tag = new CompoundTag(); tag.putByteArray("padding", new byte[8 * 1024 * 1024]);
        region.add(0, 0, tag, RegionFileVersion.VERSION_DEFLATE, false); region.write();
        var polls = new AtomicInteger(); cancelled = false;
        try {
            EntityRegionCandidates.collect(root, new EntityRegionCandidates.Bounds(0, 0, 0, 0),
                    () -> polls.incrementAndGet() >= 40);
        } catch (CancellationException expected) { cancelled = true; }
        check(cancelled && polls.get() == 40, "a single compressed NBT record checks cancellation while skipping its payload");
    }

    private static CompoundTag modern(boolean populated) {
        var root = new CompoundTag(); root.putInt("DataVersion", 3955);
        var entities = new ListTag();
        if (populated) {
            var entity = new CompoundTag(); entity.putString("id", "fixture:no-entity-deserialization");
            var passengers = new ListTag(); passengers.add(new CompoundTag()); entity.put("Passengers", passengers);
            entities.add(entity);
        }
        root.put("Entities", entities); return root;
    }

    private static CompoundTag legacy(boolean populated, int version) {
        var root = new CompoundTag(); if (version >= 0) root.putInt("DataVersion", version);
        var level = modern(populated); level.remove("DataVersion"); root.put("Level", level); return root;
    }

    private static long pos(int x, int z) { return ChunkPos.asLong(x, z); }

    private static Map<Path, byte[]> snapshot(Path root) throws IOException {
        var result = new LinkedHashMap<Path, byte[]>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList())
                result.put(root.relativize(path), Files.readAllBytes(path));
        }
        return result;
    }

    private static boolean sameSnapshot(Map<Path, byte[]> before, Map<Path, byte[]> after) {
        return before.keySet().equals(after.keySet()) && before.entrySet().stream()
                .allMatch(entry -> Arrays.equals(entry.getValue(), after.get(entry.getKey())));
    }

    private static final class RegionFixture {
        final Path folder; final int x, z;
        final ByteBuffer header = ByteBuffer.allocate(8192);
        final ByteArrayOutputStream records = new ByteArrayOutputStream();
        int sector = 2;
        RegionFixture(Path folder, int x, int z) { this.folder = folder; this.x = x; this.z = z; }
        void add(int chunkX, int chunkZ, CompoundTag tag, RegionFileVersion version, boolean external) throws IOException {
            var compressed = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(version.wrap(compressed))) { NbtIo.write(tag, output); }
            var payload = compressed.toByteArray();
            int size = external ? 1 : (payload.length + 5 + 4095) / 4096;
            header.putInt((Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32)) * 4, sector << 8 | size);
            var record = ByteBuffer.allocate(size * 4096);
            record.putInt(external ? 1 : payload.length + 1).put((byte) (version.getId() | (external ? 128 : 0)));
            if (external) Files.write(folder.resolve("c." + chunkX + "." + chunkZ + ".mcc"), payload);
            else record.put(payload);
            records.write(record.array()); sector += size;
        }
        void write() throws IOException {
            try (var channel = FileChannel.open(folder.resolve("r." + x + "." + z + ".mca"),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (header.hasRemaining()) channel.write(header);
                var data = ByteBuffer.wrap(records.toByteArray()); while (data.hasRemaining()) channel.write(data);
            }
        }
    }

    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
