package com.example.examplemod.backup;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** First-write undo journal. Retired archive/task files are never opened or overwritten. */
public final class MapResetData extends SavedData {
    private static final Factory<MapResetData> FACTORY = new Factory<>(MapResetData::new, MapResetData::load);
    final TreeMap<Long, Entry> entries = new TreeMap<>();
    private CompoundTag protectedOriginal;
    enum EntityRecording { RECORD, IGNORE, PENDING }
    static final class Entry {
        MapDefinition map;
        String state = "PROTECTED", failure = "";
        // Keep completed journals until the next round: replay after a restart also covers a crash
        // between saved-data persistence and the corresponding chunk saves.
        final LinkedHashMap<Long, CompoundTag> changes = new LinkedHashMap<>();
        // Flat entity NBT plus vehicle UUID keeps passenger trees without duplicating passengers.
        // Absent in old saves deliberately means an empty entity baseline.
        final LinkedHashMap<UUID, CompoundTag> entities = new LinkedHashMap<>();
        final LinkedHashMap<UUID, CompoundTag> ownedEntities = new LinkedHashMap<>();
        // The visible numeric ID is reusable; entity ownership must identify this incarnation.
        // Null is reserved for pre-identity saves and accepts their legacy entity markers.
        UUID identity = UUID.randomUUID();
        UUID entityRound;
        boolean entityCapture, entityReplay;
        EntityRecording entityRecording = EntityRecording.RECORD;
        // A confirmed skip applies only to this recovery generation. Keep the original
        // records until the next round so a crash cannot turn a skip into an implicit replay.
        boolean entityRestoreSkipped, terrainRestoreSkipped;
        Entry(MapDefinition map) { this.map = map; }
        boolean managesEntities() { return entityRecording == EntityRecording.RECORD && !entityRestoreSkipped; }
        boolean needsTerrainRestore() { return !terrainRestoreSkipped && !changes.isEmpty(); }
        boolean locked() { return !state.equals("ACTIVE") && !state.equals("EDITING"); }
        Entry copy() {
            var copy = new Entry(map);
            copy.state = state; copy.failure = failure; copy.identity = identity; copy.entityRound = entityRound;
            copy.entityCapture = entityCapture; copy.entityReplay = entityReplay; copy.entityRecording = entityRecording;
            copy.entityRestoreSkipped = entityRestoreSkipped; copy.terrainRestoreSkipped = terrainRestoreSkipped;
            // Copy the indexes, not every NBT payload: policy changes never edit a payload.
            // The old entry remains available for rollback until the atomic commit succeeds.
            copy.changes.putAll(changes); copy.entities.putAll(entities); copy.ownedEntities.putAll(ownedEntities);
            return copy;
        }
        Entry editingCopy() {
            if (!state.equals("PROTECTED")) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
            // A new baseline generation also retires old ownership markers. Entities loaded
            // later must not be discarded because they still carry a previous round's marker.
            var editing = new Entry(map);
            editing.state = "EDITING";
            editing.entityRecording = entityRecording;
            return editing;
        }
        void freeze() {
            if (!state.equals("EDITING")) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_editing"));
            entityCapture = entityRecording == EntityRecording.RECORD;
            state = entityCapture ? "CAPTURING" : "PROTECTED";
        }
        boolean record(BlockPos pos, CompoundTag original) {
            return state.equals("ACTIVE") && changes.putIfAbsent(pos.asLong(), original.copy()) == null;
        }
        void prepare() {
            if (!state.equals("PROTECTED")) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
            if (entityRecording == EntityRecording.PENDING)
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.entity_recording_pending"));
            state = "PREPARED";
            entityRestoreSkipped = false; terrainRestoreSkipped = false;
            entityRound = entityRecording == EntityRecording.RECORD ? UUID.randomUUID() : null;
            entityReplay = entityRecording == EntityRecording.RECORD;
            ownedEntities.clear();
        }
        void activate() {
            if (!state.equals("PREPARED")) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.not_ready"));
            changes.clear(); state = "ACTIVE";
        }
        void end() {
            if (!state.equals("EDITING"))
                state = entityCapture ? "CAPTURING" : !needsTerrainRestore() && (!entityReplay || !managesEntities()) ? "PROTECTED" : "RESTORING";
        }
        boolean canSkipRecovery(boolean skipEntities, boolean skipTerrain) {
            return Set.of("CAPTURING", "RESTORING", "FAILED").contains(state)
                    && (skipEntities && (entityCapture || entityReplay && managesEntities())
                    || skipTerrain && needsTerrainRestore());
        }
        void skipRecovery(boolean skipEntities, boolean skipTerrain) {
            if (!canSkipRecovery(skipEntities, skipTerrain))
                throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.no_recovery_to_skip"));
            if (skipEntities) {
                entityRestoreSkipped = true;
                if (entityCapture) {
                    // An interrupted capture is not a complete baseline for another round.
                    entityCapture = false; entityRecording = EntityRecording.IGNORE;
                }
            }
            if (skipTerrain) terrainRestoreSkipped = true;
            failure = ""; end();
        }
    }
    static boolean needsEntityRecordingChoice(MapDefinition map, double sideLength) {
        return (map.maxX() - map.minX()) * (map.maxZ() - map.minZ()) > sideLength * sideLength;
    }
    static EntityRecording recordingForSave(MapDefinition map, double sideLength, Entry previous) {
        // The threshold is a creation default. Resizing or moving a map must not
        // silently replace an administrator's saved record/ignore/pending choice.
        return previous != null ? previous.entityRecording
                : needsEntityRecordingChoice(map, sideLength) ? EntityRecording.PENDING : EntityRecording.RECORD;
    }
    static MapResetData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_map_changes");
    }
    void requireWritable() {
        if (protectedOriginal != null) throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.corrupt"));
    }
    boolean readable() { return protectedOriginal == null; }
    static boolean sameRange(MapDefinition a, MapDefinition b) {
        return a.id() == b.id() && a.dimension().equals(b.dimension()) && a.minX() == b.minX()
                && a.minZ() == b.minZ() && a.maxX() == b.maxX() && a.maxZ() == b.maxZ();
    }
    static boolean contains(MapDefinition map, BlockPos pos) {
        return pos.getX() >= Math.ceil(map.minX()) && pos.getX() < Math.floor(map.maxX())
                && pos.getZ() >= Math.ceil(map.minZ()) && pos.getZ() < Math.floor(map.maxZ());
    }
    static boolean lockedAt(Iterable<Entry> entries, ResourceLocation dimension, BlockPos pos) {
        boolean locked = false;
        for (var entry : entries) if (entry.map.dimension().equals(dimension) && contains(entry.map, pos)) {
            // Active overlaps share the round's journal; EDITING cannot unlock another map.
            if (entry.state.equals("ACTIVE")) return false;
            if (entry.locked()) locked = true;
        }
        return locked;
    }
    static MapResetData load(CompoundTag tag, HolderLookup.Provider lookup) {
        var data = new MapResetData();
        if (tag.isEmpty()) return data;
        try {
            int version = tag.getInt("version");
            if ((version < 1 || version > 3) || !(tag.get("maps") instanceof ListTag list)
                    || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND || list.size() > MapZoneModel.MAX_MAPS)
                throw new IllegalArgumentException();
            for (Tag value : list) {
                var record = (CompoundTag) value;
                var e = new Entry(MapZoneModel.parse(record.getString("map")));
                if (record.contains("map_identity") && !record.hasUUID("map_identity")) throw new IllegalArgumentException();
                e.identity = record.hasUUID("map_identity") ? record.getUUID("map_identity") : null;
                e.state = record.getString("state"); e.failure = record.getString("failure");
                if (e.map.id() <= 0 || !Set.of("PROTECTED", "EDITING", "CAPTURING", "PREPARED", "ACTIVE", "RESTORING", "FAILED").contains(e.state)
                        || data.entries.putIfAbsent(e.map.id(), e) != null
                        || !(record.get("changes") instanceof ListTag changes)
                        || !changes.isEmpty() && changes.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException();
                for (Tag change : changes) {
                    var original = (CompoundTag) change;
                    BlockPos pos = BlockPos.of(original.getLong("pos"));
                    if (!original.contains("pos", Tag.TAG_LONG) || !contains(e.map, pos)
                            || !original.contains("state", Tag.TAG_COMPOUND)
                            || original.getCompound("state").getString("Name").isEmpty()
                            || e.changes.putIfAbsent(pos.asLong(), original.copy()) != null) throw new IllegalArgumentException();
                }
                e.entityCapture = record.getBoolean("entity_capture");
                if (version >= 3) {
                    e.entityRecording = EntityRecording.valueOf(record.getString("entity_recording"));
                    e.entityRestoreSkipped = record.getBoolean("entity_restore_skipped");
                    e.terrainRestoreSkipped = record.getBoolean("terrain_restore_skipped");
                }
                e.entityReplay = record.getBoolean("entity_replay")
                        || version < 3 && Set.of("ACTIVE", "PREPARED", "RESTORING").contains(e.state);
                if (e.entityCapture && (e.entityRecording != EntityRecording.RECORD || e.entityRestoreSkipped)
                        || e.state.equals("CAPTURING") && !e.entityCapture
                        || e.entityRecording == EntityRecording.PENDING
                        && (!Set.of("PROTECTED", "EDITING").contains(e.state) || e.entityReplay))
                    throw new IllegalArgumentException();
                if (record.hasUUID("entity_round")) e.entityRound = record.getUUID("entity_round");
                readEntities(record, "entities", e.entities, true);
                readEntities(record, "owned_entities", e.ownedEntities, false);
                if (e.state.equals("EDITING") && (!e.changes.isEmpty() || !e.entities.isEmpty()
                        || !e.ownedEntities.isEmpty() || e.entityRound != null || e.entityCapture || e.entityReplay))
                    throw new IllegalArgumentException();
                if (!e.state.equals("FAILED")) e.end(); // Never resume a saved match.
                data.setDirty();
            }
        } catch (RuntimeException error) { data.entries.clear(); data.protectedOriginal = tag.copy(); }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (protectedOriginal != null) return protectedOriginal.copy();
        tag.putInt("version", 3); var maps = new ListTag();
        for (var e : entries.values()) {
            var record = new CompoundTag(); record.putString("map", MapZoneModel.toJson(e.map));
            if (e.identity != null) record.putUUID("map_identity", e.identity);
            record.putString("state", e.state); record.putString("failure", e.failure);
            record.putBoolean("entity_capture", e.entityCapture);
            record.putBoolean("entity_replay", e.entityReplay);
            record.putString("entity_recording", e.entityRecording.name());
            record.putBoolean("entity_restore_skipped", e.entityRestoreSkipped);
            record.putBoolean("terrain_restore_skipped", e.terrainRestoreSkipped);
            if (e.entityRound != null) record.putUUID("entity_round", e.entityRound);
            var entities = new ListTag(); e.entities.values().forEach(value -> entities.add(value.copy()));
            record.put("entities", entities);
            var owned = new ListTag(); e.ownedEntities.values().forEach(value -> owned.add(value.copy()));
            record.put("owned_entities", owned);
            var changes = new ListTag(); e.changes.values().forEach(value -> changes.add(value.copy()));
            record.put("changes", changes); maps.add(record);
        }
        tag.put("maps", maps); return tag;
    }
    /** Commit an editing boundary before commands can mutate the world under its new state. */
    void persist(MinecraftServer server) {
        requireWritable();
        // SavedData.save queues writes. Drain older snapshots before this atomic replacement,
        // otherwise an old completed journal could overwrite the edit state after it returns.
        IOUtilities.waitUntilIOWorkerComplete();
        var tag = new CompoundTag();
        tag.put("data", save(new CompoundTag(), server.registryAccess()));
        NbtUtils.addCurrentDataVersion(tag);
        try {
            IOUtilities.writeNbtCompressed(tag, server.getWorldPath(LevelResource.ROOT)
                    .resolve("data").resolve("tacz_pubg_map_changes.dat"));
            setDirty(false);
        } catch (IOException error) {
            setDirty();
            throw new IllegalStateException(Texts.tr("message.tacz_pubg.journal.edit_save_failed"), error);
        }
    }
    private static void readEntities(CompoundTag record, String key,
            LinkedHashMap<UUID, CompoundTag> output, boolean baseline) {
        if (!record.contains(key)) return;
        if (!(record.get(key) instanceof ListTag list)
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException();
        for (var value : list) {
            var entity = (CompoundTag) value;
            if (!entity.hasUUID("uuid") || output.putIfAbsent(entity.getUUID("uuid"), entity.copy()) != null)
                throw new IllegalArgumentException();
            if (baseline) {
                var nbt = entity.getCompound("nbt");
                if (!nbt.hasUUID("UUID") || !nbt.getUUID("UUID").equals(entity.getUUID("uuid"))
                        || nbt.getString("id").isEmpty() || nbt.getList("Pos", Tag.TAG_DOUBLE).size() != 3
                        || nbt.contains("Passengers")) throw new IllegalArgumentException();
                var position = nbt.getList("Pos", Tag.TAG_DOUBLE);
                for (int axis = 0; axis < 3; axis++) if (!Double.isFinite(position.getDouble(axis))) throw new IllegalArgumentException();
                if (entity.contains("vehicle") && !entity.hasUUID("vehicle")) throw new IllegalArgumentException();
            } else if (entity.getString("dimension").isEmpty() || !entity.contains("chunk", Tag.TAG_LONG)) {
                throw new IllegalArgumentException();
            }
        }
    }
}
