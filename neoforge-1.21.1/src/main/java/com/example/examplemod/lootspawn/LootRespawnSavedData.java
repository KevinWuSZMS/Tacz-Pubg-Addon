package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.spawn.SpawnAreaSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pending work survives normal world saves, restarts and unloaded dimensions. */
public final class LootRespawnSavedData extends SavedData {
    private static final Factory<LootRespawnSavedData> FACTORY =
            new Factory<>(LootRespawnSavedData::new, LootRespawnSavedData::load);
    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private long epoch;
    private String documentJson = "";
    @Nullable private Document document;
    @Nullable private UUID requester;
    private int totalRegions;
    private int completedRegions;
    private long spawnedEntities;
    private boolean completionNotified;
    private String snapshotError = "";

    public record Job(long id, ResourceLocation dimension, List<BlockPos> floors, long seed, String failure, double rarity, com.example.examplemod.lootconfig.LootTier tier) {
        public Job {
            java.util.Objects.requireNonNull(tier, "tier");
            floors = List.copyOf(floors);
            if (!Double.isFinite(rarity) || rarity < 0 || rarity > 1000) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_respawn_saved_data.refresh_task_region_rarity_multiplier_between"));
            }
        }
        public Job(long id, ResourceLocation dimension, List<BlockPos> floors, long seed, String failure, double rarity) {
            this(id, dimension, floors, seed, failure, rarity, com.example.examplemod.lootconfig.LootTier.fromMultiplier(rarity));
        }
        public Job(long id, ResourceLocation dimension, List<BlockPos> floors, long seed, String failure) {
            this(id, dimension, floors, seed, failure, 1.0);
        }
        public boolean failed() { return !failure.isBlank(); }
    }

    public static LootRespawnSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "tacz_pubg_loot_respawn");
    }

    static LootRespawnSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        LootRespawnSavedData data = new LootRespawnSavedData();
        data.snapshotError = tag.getString("snapshotError");
        data.epoch = Math.max(0, tag.getLong("epoch"));
        data.documentJson = tag.getString("document");
        if (!data.documentJson.isBlank()) {
            try {
                data.document = LootConfigModel.parse(data.documentJson);
                data.documentJson = LootConfigModel.toJson(data.document); // Normalize legacy group snapshots to unique items.
            } catch (RuntimeException error) {
                data.snapshotError = Texts.tr("message.tacz_pubg.loot_respawn_saved_data.refresh_task_configuration_snapshot_corrupt_fix_configuration_run");
                ExampleMod.LOGGER.error(data.snapshotError, error);
            }
        }
        if (tag.hasUUID("requester")) data.requester = tag.getUUID("requester");
        data.totalRegions = Math.max(0, tag.getInt("totalRegions"));
        data.completedRegions = Math.max(0, tag.getInt("completedRegions"));
        data.spawnedEntities = Math.max(0, tag.getLong("spawnedEntities"));
        data.completionNotified = tag.getBoolean("completionNotified");
        ListTag pending = tag.getList("jobs", Tag.TAG_COMPOUND);
        int floors = 0;
        for (int i = 0; i < pending.size(); i++) {
            CompoundTag saved = pending.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(saved.getString("dimension"));
            long[] packed = saved.getLongArray("floors");
            if (dimension == null || packed.length == 0 || data.jobs.size() >= SpawnAreaSavedData.MAX_TOTAL_FLOORS
                    || floors + (long) packed.length > SpawnAreaSavedData.MAX_TOTAL_FLOORS) {
                data.snapshotError = Texts.tr("message.tacz_pubg.loot_respawn_saved_data.refresh_task_records_exceed_limit_corrupt_run_itemrespawn");
                break;
            }
            long id = saved.getLong("id");
            if (data.jobs.containsKey(id)) {
                data.snapshotError = Texts.tr("message.tacz_pubg.loot_respawn_saved_data.duplicate_refresh_task_id_run_itemrespawn_again");
                break;
            }
            List<BlockPos> positions = new ArrayList<>(packed.length);
            for (long position : packed) positions.add(BlockPos.of(position));
            floors += positions.size();
            String failure = saved.getString("failure");
            double rarity = saved.contains("rarity", Tag.TAG_ANY_NUMERIC) ? saved.getDouble("rarity") : 1.0;
            if (!Double.isFinite(rarity) || rarity < 0 || rarity > 1000) {
                data.snapshotError = Texts.tr("message.tacz_pubg.loot_respawn_saved_data.refresh_task_region_rarity_multiplier_corrupt_run_itemrespawn");
                break;
            }
            com.example.examplemod.lootconfig.LootTier tier;
            try {
                tier = saved.contains("tier", Tag.TAG_STRING) ? com.example.examplemod.lootconfig.LootTier.fromId(saved.getString("tier"))
                        : com.example.examplemod.lootconfig.LootTier.fromMultiplier(rarity);
            } catch (IllegalArgumentException invalidTier) {
                data.snapshotError = Texts.tr("message.tacz_pubg.loot_respawn_saved_data.refresh_task_loot_tier_corrupt_run_itemrespawn_again"); break;
            }
            data.jobs.put(id, new Job(id, dimension, positions, saved.getLong("seed"),
                    Texts.limit(failure, Texts.MAX_MESSAGE_LENGTH), rarity, tier));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 2);
        tag.putLong("epoch", epoch);
        tag.putString("document", documentJson);
        tag.putString("snapshotError", snapshotError);
        if (requester != null) tag.putUUID("requester", requester);
        tag.putInt("totalRegions", totalRegions);
        tag.putInt("completedRegions", completedRegions);
        tag.putLong("spawnedEntities", spawnedEntities);
        tag.putBoolean("completionNotified", completionNotified);
        ListTag pending = new ListTag();
        for (Job job : jobs.values()) {
            CompoundTag saved = new CompoundTag();
            saved.putLong("id", job.id());
            saved.putString("dimension", job.dimension().toString());
            saved.putLongArray("floors", job.floors().stream().mapToLong(BlockPos::asLong).toArray());
            saved.putLong("seed", job.seed());
            saved.putString("failure", job.failure());
            saved.putDouble("rarity", job.rarity());
            saved.putString("tier", job.tier().id());
            pending.add(saved);
        }
        tag.put("jobs", pending);
        return tag;
    }

    public void begin(Document config, List<Job> planned, @Nullable UUID requester) {
        String json = LootConfigModel.toJson(config);
        // Validate serialization before replacing the previous epoch's pending work.
        LootConfigModel.parse(json);
        epoch = epoch == Long.MAX_VALUE ? 1 : epoch + 1;
        document = config;
        documentJson = json;
        this.requester = requester;
        jobs.clear();
        for (Job job : planned) jobs.put(job.id(), job);
        totalRegions = planned.size();
        completedRegions = 0;
        spawnedEntities = 0;
        completionNotified = false;
        snapshotError = "";
        setDirty();
    }

    public void cancel(com.example.examplemod.zones.MapZoneModel.MapDefinition map) {
        boolean changed = false;
        for (var job : List.copyOf(jobs.values())) {
            var keep = job.floors.stream().filter(p -> !com.example.examplemod.spawn.MapSpawnBounds.contains(map, job.dimension, p)).toList();
            if (keep.size() == job.floors.size()) continue;
            changed = true;
            if (keep.isEmpty()) jobs.remove(job.id);
            else jobs.put(job.id, new Job(job.id, job.dimension, keep, job.seed, job.failure, job.rarity, job.tier));
        }
        if (changed) { epoch++; setDirty(); }
    }
    public long epoch() { return epoch; }
    public List<Job> jobs() { return List.copyOf(jobs.values()); }
    @Nullable public Job job(long id) { return jobs.get(id); }
    @Nullable public Document document() { return document; }
    @Nullable public UUID requester() { return requester; }
    public int totalRegions() { return totalRegions; }
    public int completedRegions() { return completedRegions; }
    public long spawnedEntities() { return spawnedEntities; }
    public String snapshotError() { return snapshotError; }
    public boolean completionNotified() { return completionNotified; }
    public int failedCount() { return (int) jobs.values().stream().filter(Job::failed).count(); }
    public int pendingCount() { return jobs.size() - failedCount(); }

    public void completed(long id, int generated) {
        if (jobs.remove(id) != null) {
            completedRegions++;
            spawnedEntities += generated;
            setDirty();
        }
    }

    public void failed(long id, String reason) {
        Job job = jobs.get(id);
        if (job != null) {
            String safe = reason == null ? Texts.tr("message.tacz_pubg.loot_respawn_saved_data.unknown_spawn_error") : reason;
            safe = Texts.limit(safe, Texts.MAX_MESSAGE_LENGTH);
            jobs.put(id, new Job(job.id(), job.dimension(), job.floors(), job.seed(), safe, job.rarity(), job.tier()));
            setDirty();
        }
    }

    public void notified() {
        completionNotified = true;
        setDirty();
    }
}
