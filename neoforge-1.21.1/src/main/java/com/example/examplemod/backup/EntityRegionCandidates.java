package com.example.examplemod.backup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.SkipAll;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Read-only disk index. Never opens a writable RegionFile or generates/loads terrain. */
final class EntityRegionCandidates {
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    record Bounds(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        boolean intersectsRegion(int x, int z) {
            long left = (long) x * 32, top = (long) z * 32;
            return left <= maxX && left + 31 >= minX && top <= maxZ && top + 31 >= minZ;
        }
    }

    private EntityRegionCandidates() {}

    static Set<Long> collect(Path dimension, Bounds bounds, BooleanSupplier cancelled) {
        try {
            checkCancellation(cancelled);
            var result = new LinkedHashSet<Long>();
            scan(dimension.resolve("entities"), bounds, false, result, cancelled);
            // Unconverted pre-1.18 chunks can still contain Level.Entities. A current level.dat
            // version does not prove those distant chunks were upgraded. Probe only existing
            // records, halting at modern DataVersion or at the legacy entity-list header.
            scan(dimension.resolve("region"), bounds, true, result, cancelled);
            // Packed chunk coordinates hash as x XOR z: dense maps have very few
            // distinct hashes. Set.copyOf rebuilds them with quadratic linear probing;
            // preserve LinkedHashSet's tree bins and wrap this privately owned builder.
            return Collections.unmodifiableSet(result);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static void scan(Path folder, Bounds bounds, boolean legacy, Set<Long> result,
                             BooleanSupplier cancelled) throws IOException {
        if (!Files.exists(folder)) return;
        try (var paths = Files.newDirectoryStream(folder, "r.*.*.mca")) {
            for (var path : paths) {
                checkCancellation(cancelled);
                var match = REGION.matcher(path.getFileName().toString());
                if (!match.matches()) continue;
                int regionX, regionZ;
                try { regionX = Integer.parseInt(match.group(1)); regionZ = Integer.parseInt(match.group(2)); }
                catch (NumberFormatException ignored) { continue; }
                if (!bounds.intersectsRegion(regionX, regionZ)) continue;
                try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                    var header = ByteBuffer.allocate(4096);
                    readFully(channel, header, 0); header.flip();
                    for (int index = 0; index < 1024; index++) {
                        checkCancellation(cancelled);
                        int packed = header.getInt();
                        if (packed == 0) continue;
                        long x = (long) regionX * 32 + index % 32, z = (long) regionZ * 32 + index / 32;
                        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE
                                || !bounds.contains((int) x, (int) z)) continue;
                        long chunk = ChunkPos.asLong((int) x, (int) z);
                        if (result.contains(chunk)) continue;
                        // An occupied entity slot can still have an empty Entities list (for
                        // example after a mod entity refused serialization). Read only the
                        // list header, never deserialize entities or touch terrain here.
                        if (hasEntities(channel, folder, packed, (int) x, (int) z, legacy, cancelled)) result.add(chunk);
                    }
                }
            }
        }
    }

    static boolean legacyEntities(FileChannel channel, Path folder, int packed, int x, int z) throws IOException {
        return hasEntities(channel, folder, packed, x, z, true, () -> false);
    }

    private static boolean hasEntities(FileChannel channel, Path folder, int packed, int x, int z,
                                       boolean legacy, BooleanSupplier cancelled) throws IOException {
        checkCancellation(cancelled);
        int sector = packed >>> 8, count = packed & 255;
        if (sector < 2 || count == 0) throw new IOException("Invalid region location for " + x + "," + z);
        long position = (long) sector * 4096;
        var chunkHeader = ByteBuffer.allocate(5); readFully(channel, chunkHeader, position); chunkHeader.flip();
        int length = chunkHeader.getInt(); int compression = chunkHeader.get() & 255;
        var version = RegionFileVersion.fromId(compression & 127);
        if (version == null || version == RegionFileVersion.VERSION_CUSTOM)
            throw new IOException("Unsupported region compression " + compression + " at " + x + "," + z);
        InputStream input;
        if ((compression & 128) != 0) {
            if (length != 1) throw new IOException("Invalid external region record at " + x + "," + z);
            input = Files.newInputStream(folder.resolve("c." + x + "." + z + ".mcc"));
        } else {
            if (length <= 1 || length > (long) count * 4096 - 4)
                throw new IOException("Invalid region record length at " + x + "," + z);
            input = new SliceInput(channel, position + 5, length - 1);
        }
        // Own the raw input separately: compression initialization itself can fail, and
        // external .mcc streams must be closed even then. Check both sides of buffering so
        // cancellation also interrupts one large skipped/compressed NBT payload promptly.
        try (var source = new CancellableInput(input, cancelled);
             var decompressed = new DataInputStream(new CancellableInput(version.wrap(source), cancelled))) {
            var visitor = new EntityProbe(legacy);
            NbtIo.parse(decompressed, visitor, NbtAccounter.create(64L * 1024 * 1024));
            return visitor.hasEntities;
        }
    }

    /** A nonempty list is sufficient, including roots with nested passengers. No entity allocation. */
    private static final class EntityProbe implements SkipAll {
        final boolean legacy;
        int depth;
        boolean dataVersion, hasEntities;
        EntityProbe(boolean legacy) { this.legacy = legacy; }
        @Override public EntryResult visitEntry(TagType<?> type) { return EntryResult.ENTER; }
        @Override public EntryResult visitEntry(TagType<?> type, String name) {
            dataVersion = depth == 0 && name.equals("DataVersion") && type == IntTag.TYPE;
            if (dataVersion) return EntryResult.ENTER;
            if (legacy && depth == 0 && name.equals("Level") && type == CompoundTag.TYPE) {
                depth++; return EntryResult.ENTER;
            }
            return depth == (legacy ? 1 : 0) && name.equals("Entities") && type == ListTag.TYPE
                    ? EntryResult.ENTER : EntryResult.SKIP;
        }
        @Override public ValueResult visit(int value) {
            // 1.18+ terrain has no legacy entity list; do not walk sections/heightmaps.
            return legacy && dataVersion && value >= 2844 ? ValueResult.HALT : ValueResult.CONTINUE;
        }
        @Override public ValueResult visitList(TagType<?> type, int size) {
            hasEntities = size > 0 && type == CompoundTag.TYPE; return ValueResult.HALT;
        }
        @Override public ValueResult visitContainerEnd() { depth--; return ValueResult.CONTINUE; }
    }

    private static void checkCancellation(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Entity candidate indexing cancelled");
    }
    private static final class CancellableInput extends FilterInputStream {
        final BooleanSupplier cancelled;
        CancellableInput(InputStream input, BooleanSupplier cancelled) { super(input); this.cancelled = cancelled; }
        @Override public int read() throws IOException { checkCancellation(cancelled); return in.read(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancellation(cancelled); return in.read(bytes, offset, Math.min(length, 8192));
        }
        @Override public long skip(long count) throws IOException {
            checkCancellation(cancelled); return in.skip(Math.min(count, 8192));
        }
    }
    private static void readFully(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, position + bytes.position());
            if (read < 0) throw new EOFException("Truncated region file");
        }
    }
    private static final class SliceInput extends InputStream {
        final FileChannel channel; long position, remaining;
        SliceInput(FileChannel channel, long position, long length) {
            this.channel = channel; this.position = position; this.remaining = length;
        }
        @Override public int read() throws IOException {
            var value = new byte[1]; return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int read = channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, remaining)), position);
            if (read < 0) throw new EOFException("Truncated region record");
            position += read; remaining -= read; return read;
        }
        @Override public long skip(long count) {
            long skipped = Math.min(Math.max(count, 0), remaining); position += skipped; remaining -= skipped; return skipped;
        }
        // Closing one record must not close the shared read-only region channel.
    }
}
