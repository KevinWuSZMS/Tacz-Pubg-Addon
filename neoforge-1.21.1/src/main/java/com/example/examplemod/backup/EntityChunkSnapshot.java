package com.example.examplemod.backup;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;

/** Work scales with existing entities/sections, never with the vertical distance between them. */
final class EntityChunkSnapshot {
    private EntityChunkSnapshot() {}

    static <T extends EntityAccess> List<T> collect(EntitySectionStorage<T> storage, long chunk) {
        // Snapshot before removals mutate the same section index. Include hidden loaded sections:
        // their entities must also be cleaned up, and entities above/below build height are valid.
        try (var sections = storage.getExistingSectionsInChunk(chunk)) {
            return sections.flatMap(EntitySection::getEntities).toList();
        }
    }

    static <T extends EntityAccess> Set<Long> nonEmptyChunks(EntitySectionStorage<T> storage) {
        var chunks = new LinkedHashSet<Long>();
        // Includes HIDDEN loaded entities, not only the level's visible entity getter.
        for (long chunk : storage.getAllChunksWithExistingSections()) {
            try (var sections = storage.getExistingSectionsInChunk(chunk)) {
                if (sections.anyMatch(section -> !section.isEmpty())) chunks.add(chunk);
            }
        }
        return chunks;
    }
}
