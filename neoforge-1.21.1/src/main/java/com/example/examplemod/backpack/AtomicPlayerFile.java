package com.example.examplemod.backpack;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Shared record commit, also exercised directly by the non-game regression checks. */
final class AtomicPlayerFile {
    private AtomicPlayerFile() {}
    static void commit(Path destination, CompoundTag data) throws IOException {
        Path directory = destination.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "match-player-", ".dat");
            NbtIo.writeCompressed(data, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (!NbtIo.readCompressed(temporary, NbtAccounter.unlimitedHeap()).equals(data))
                throw new IOException("Player snapshot verification mismatch");
            if (Files.exists(destination)) Files.copy(destination,
                    destination.resolveSibling(destination.getFileName() + "_old"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }
}
