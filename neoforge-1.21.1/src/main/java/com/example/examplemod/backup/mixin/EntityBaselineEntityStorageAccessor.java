package com.example.examplemod.backup.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Obtain a nonblocking I/O barrier without running entity deserialization on the server thread. */
@Mixin(EntityStorage.class)
public interface EntityBaselineEntityStorageAccessor {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage taczPubg$regionStorage();
}
