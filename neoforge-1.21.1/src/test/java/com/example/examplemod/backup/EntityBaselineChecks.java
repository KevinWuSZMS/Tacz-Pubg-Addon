package com.example.examplemod.backup;

import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Saved-data migration/recovery fixtures only; no level, entity spawning, or game startup. */
public final class EntityBaselineChecks {
    private static int checks;
    public static void main(String[] args) {
        sparseChunkSnapshot();
        var map = new MapDefinition(1, "entity fixture", ResourceLocation.parse("minecraft:overworld"),
                -16, -16, 32, 32, null, 1, List.of());
        var data = new MapResetData(); var entry = new MapResetData.Entry(map); data.entries.put(1L, entry);
        var legacy = data.save(new CompoundTag(), null); legacy.putInt("version", 1);
        var old = legacy.getList("maps", Tag.TAG_COMPOUND).getCompound(0);
        for (var key : List.of("entities", "owned_entities", "entity_capture", "entity_replay", "entity_round",
                "entity_recording", "entity_restore_skipped", "terrain_restore_skipped")) old.remove(key);
        var migrated = MapResetData.load(legacy, null); migrated.requireWritable();
        check(migrated.entries.get(1L).entities.isEmpty(), "v1 maps deliberately have an empty entity baseline");
        check(migrated.entries.get(1L).state.equals("PROTECTED"), "migration does not invent a capture or active match");

        var vehicle = UUID.randomUUID(); var rider = UUID.randomUUID();
        var boat = entity(vehicle, "minecraft:boat"); var passenger = entity(rider, "minecraft:pig");
        passenger.putUUID("vehicle", vehicle); passenger.putInt("passenger_index", 0);
        var extra = new CompoundTag(); extra.putString("fixture_inventory", "keep every nested component");
        boat.getCompound("nbt").put("NeoForgeData", extra);
        entry.entities.put(vehicle, boat); entry.entities.put(rider, passenger);
        entry.prepare();
        var owned = new CompoundTag(); owned.putUUID("uuid", rider); owned.putString("dimension", "minecraft:the_nether");
        owned.putLong("chunk", 12345); entry.ownedEntities.put(rider, owned);
        var saved = data.save(new CompoundTag(), null);
        check(saved.getInt("version") == 3, "new saves persist the entity recording policy and recovery decisions");
        var version2 = saved.copy(); version2.putInt("version", 2);
        var version2Entry = version2.getList("maps", Tag.TAG_COMPOUND).getCompound(0);
        for (var key : List.of("entity_recording", "entity_restore_skipped", "terrain_restore_skipped", "entity_replay"))
            version2Entry.remove(key);
        var migrated2 = MapResetData.load(version2, null); migrated2.requireWritable();
        var oldPrepared = migrated2.entries.get(1L);
        check(oldPrepared.entityRecording == MapResetData.EntityRecording.RECORD && oldPrepared.entityReplay
                        && oldPrepared.state.equals("RESTORING"),
                "v2 prepared matches retain original entity recovery even when the old replay flag is absent");
        check(oldPrepared.entities.equals(entry.entities) && oldPrepared.ownedEntities.equals(entry.ownedEntities)
                        && oldPrepared.entityRound.equals(entry.entityRound) && oldPrepared.identity.equals(entry.identity),
                "v2 migration retains full baseline NBT, ownership, round and incarnation identities");
        var reloaded = MapResetData.load(saved, null); reloaded.requireWritable();
        var restored = reloaded.entries.get(1L);
        check(restored.entities.equals(entry.entities), "all original entity NBT, UUIDs and passenger order survive persistence");
        check(restored.entities.get(rider).getUUID("vehicle").equals(vehicle), "vehicle UUID relationship survives persistence");
        check(restored.entityRound.equals(entry.entityRound) && restored.ownedEntities.equals(entry.ownedEntities),
                "round identity and cross-dimension cleanup locations survive persistence");
        check(restored.state.equals("RESTORING") && restored.entityReplay, "PREPARED restart clears transient entities instead of resuming");
        entry.activate();
        check(MapResetData.load(data.save(new CompoundTag(), null), null).entries.get(1L).state.equals("RESTORING"),
                "ACTIVE restart restores entities even without a single changed block");
        entry.end(); check(entry.state.equals("RESTORING"), "an unchanged terrain round still needs entity cleanup");
        entry.state = "PROTECTED";
        check(MapResetData.load(data.save(new CompoundTag(), null), null).entries.get(1L).state.equals("RESTORING"),
                "completed restoration remains replayable across entity-storage save ordering");
        entry.entityCapture = true; entry.state = "CAPTURING";
        restored = MapResetData.load(data.save(new CompoundTag(), null), null).entries.get(1L);
        check(restored.entityCapture && restored.state.equals("CAPTURING") && restored.entities.equals(entry.entities),
                "an interrupted initial capture keeps the partial first snapshots and resumes capture");
        System.out.println("EntityBaselineChecks: " + checks + " sparse-query, migration, NBT and restart checks passed; no game launched.");
    }
    /** A tall AABB would enter Lithium's per-Y loop. This fixture rejects that query path outright. */
    private static void sparseChunkSnapshot() {
        var storage = new SparseStorage();
        var chunk = new ChunkPos(-50, 8);
        var below = new FixtureEntity(1, new BlockPos(-799, -7_000_000, 129));
        var normal = new FixtureEntity(2, new BlockPos(-799, 64, 129));
        var above = new FixtureEntity(3, new BlockPos(-799, 7_000_000, 129));
        var adjacent = new FixtureEntity(4, new BlockPos(-783, 64, 129));
        for (var entity : List.of(below, normal, above, adjacent))
            storage.getOrCreateSection(SectionPos.asLong(entity.blockPosition())).add(entity);
        // Hidden sections are loaded from disk too; cleanup must not depend on player visibility.
        var snapshot = EntityChunkSnapshot.collect(storage, chunk.toLong());
        check(snapshot.size() == 3 && snapshot.containsAll(List.of(below, normal, above)),
                "negative, ordinary and very high entities are captured without a vertical range scan");
        check(!snapshot.contains(adjacent), "adjacent chunks are not included");
        check(storage.visited == 3, "work visits only three existing sections despite fourteen million blocks of height");
        check(storage.getSection(SectionPos.asLong(normal.blockPosition())).getStatus() == Visibility.HIDDEN,
                "loaded hidden sections remain eligible for cleanup");
        for (var entity : snapshot) storage.getSection(SectionPos.asLong(entity.blockPosition())).remove(entity);
        check(snapshot.size() == 3, "snapshot stays stable while removals mutate the live entity index");
        storage.visited = 0;
        check(EntityChunkSnapshot.collect(storage, new ChunkPos(-60, 8).toLong()).isEmpty() && storage.visited == 0,
                "an empty chunk does no section work and never invokes a spatial query");
    }
    private static final class SparseStorage extends EntitySectionStorage<FixtureEntity> {
        int visited;
        SparseStorage() { super(FixtureEntity.class, ignored -> Visibility.HIDDEN); }
        @Override public Stream<EntitySection<FixtureEntity>> getExistingSectionsInChunk(long chunk) {
            return super.getExistingSectionsInChunk(chunk).peek(section -> visited++);
        }
        @Override public void forEachAccessibleNonEmptySection(AABB box,
                AbortableIterationConsumer<EntitySection<FixtureEntity>> consumer) {
            throw new AssertionError("Cleanup must not call the Lithium AABB query path");
        }
    }
    private record FixtureEntity(int id, BlockPos blockPosition) implements EntityAccess {
        @Override public int getId() { return id; }
        @Override public UUID getUUID() { return new UUID(0, id); }
        @Override public AABB getBoundingBox() { return new AABB(blockPosition); }
        @Override public void setLevelCallback(EntityInLevelCallback callback) {}
        @Override public Stream<FixtureEntity> getSelfAndPassengers() { return Stream.of(this); }
        @Override public Stream<FixtureEntity> getPassengersAndSelf() { return Stream.of(this); }
        @Override public void setRemoved(Entity.RemovalReason reason) {}
        @Override public boolean shouldBeSaved() { return true; }
        @Override public boolean isAlwaysTicking() { return false; }
    }
    private static CompoundTag entity(UUID uuid, String type) {
        var nbt = new CompoundTag(); nbt.putUUID("UUID", uuid); nbt.putString("id", type);
        var position = new ListTag(); position.add(DoubleTag.valueOf(1)); position.add(DoubleTag.valueOf(70)); position.add(DoubleTag.valueOf(1));
        nbt.put("Pos", position);
        var record = new CompoundTag(); record.putUUID("uuid", uuid); record.put("nbt", nbt); return record;
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
