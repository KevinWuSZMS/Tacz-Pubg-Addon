package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import java.util.ArrayList;
import com.example.examplemod.lootconfig.LootTier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/** Approved floor shapes and stable region identities, stored once for the whole save. */
public final class SpawnAreaSavedData extends SavedData {
    public static final int MAX_FLOORS_PER_DIMENSION = 65_536;
    public static final int MAX_TOTAL_FLOORS = 262_144;
    public static final double MAX_RARITY = 1_000;
    private static final String FILE_NAME = "tacz_pubg_spawn_areas";
    private static final Factory<SpawnAreaSavedData> FACTORY =
            new Factory<>(SpawnAreaSavedData::new, SpawnAreaSavedData::load);

    private final Map<ResourceLocation, Set<BlockPos>> floors = new HashMap<>();
    private final Map<ResourceLocation, Map<Long, Set<BlockPos>>> chunks = new HashMap<>();
    private final Map<ResourceLocation, SpawnAreaGroups.GroupIndex> groupCache = new HashMap<>();
    private final Map<Long, Region> regionById = new TreeMap<>();
    private final Map<ResourceLocation, Map<Long, Long>> regionByFloor = new HashMap<>();
    private final Set<UUID> visibleAuthors = new HashSet<>();
    private int totalFloors;
    private long revision;
    private long nextRegionId = 1;
    @Nullable private MinecraftServer owner;

    /** floors are the actual shape; min/max are information only, never an approved rectangle. */
    public record Region(long id, ResourceLocation dimension, List<BlockPos> floors, double rarity, BlockPos anchor, LootTier tier) {
        public Region {
            if (id <= 0 || id == Long.MAX_VALUE) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.invalid_region_id"));
            Objects.requireNonNull(dimension, "dimension");
            validateRarity(rarity);
            Objects.requireNonNull(tier, "tier");
            floors = floors.stream().map(BlockPos::immutable).distinct().sorted(SpawnRegionTopology.POSITION_ORDER).toList();
            if (floors.isEmpty()) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.a_region_needs_approved_floors"));
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
            if (!floors.contains(anchor)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.region_anchor_must_be_an_approved_floor"));
        }

        public Region(long id, ResourceLocation dimension, List<BlockPos> floors, double rarity, BlockPos anchor) {
            this(id, dimension, floors, rarity, anchor, LootTier.fromMultiplier(rarity));
        }
        public Region(long id, ResourceLocation dimension, List<BlockPos> floors, double rarity) {
            this(id, dimension, floors, rarity, floors.stream().min(SpawnRegionTopology.POSITION_ORDER)
                    .orElseThrow(() -> new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.a_region_needs_approved_floors"))));
        }

        public int area() { return floors.size(); }
        public BlockPos min() {
            int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
            for (BlockPos floor : floors) { x = Math.min(x, floor.getX()); y = Math.min(y, floor.getY()); z = Math.min(z, floor.getZ()); }
            return new BlockPos(x, y, z);
        }
        public BlockPos max() {
            int x = Integer.MIN_VALUE, y = Integer.MIN_VALUE, z = Integer.MIN_VALUE;
            for (BlockPos floor : floors) { x = Math.max(x, floor.getX()); y = Math.max(y, floor.getY()); z = Math.max(z, floor.getZ()); }
            return new BlockPos(x, y, z);
        }
    }

    public static SpawnAreaSavedData get(MinecraftServer server) {
        var data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
        data.owner = server;
        return data;
    }

    // Package access permits NBT round-trip checks without creating a server or reading real saves.
    static SpawnAreaSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SpawnAreaSavedData data = new SpawnAreaSavedData();
        int ignored = 0;
        if (tag.getInt("version") >= 2) {
            data.nextRegionId = Math.max(1, tag.getLong("next_region_id"));
            ListTag savedRegions = tag.getList("regions", Tag.TAG_COMPOUND);
            // Reserve the whole persisted ID range before assigning replacements to malformed records.
            for (int i = 0; i < savedRegions.size(); i++) {
                long id = savedRegions.getCompound(i).getLong("id");
                if (id > 0 && id < Long.MAX_VALUE) data.nextRegionId = Math.max(data.nextRegionId, id + 1);
            }
            Set<Long> usedIds = new HashSet<>();
            Map<ResourceLocation, List<Region>> previous = new HashMap<>();
            for (int i = 0; i < savedRegions.size(); i++) {
                CompoundTag saved = savedRegions.getCompound(i);
                ResourceLocation dimension = ResourceLocation.tryParse(saved.getString("dimension"));
                if (dimension == null) { ignored++; continue; }
                List<BlockPos> accepted = new ArrayList<>();
                for (long packed : saved.getLongArray("floors")) {
                    BlockPos floor = BlockPos.of(packed);
                    if (data.contains(dimension, floor)) { ignored++; continue; }
                    if (data.count(dimension) >= MAX_FLOORS_PER_DIMENSION || data.totalFloors >= MAX_TOTAL_FLOORS) {
                        ignored++; continue;
                    }
                    data.addUnchecked(dimension, floor);
                    accepted.add(floor);
                }
                if (accepted.isEmpty()) continue;
                long id = saved.getLong("id");
                if (id <= 0 || id == Long.MAX_VALUE || !usedIds.add(id)) {
                    id = data.allocateRegionId();
                    usedIds.add(id);
                    ignored++;
                }
                double rarity = saved.getDouble("rarity");
                if (!validRarity(rarity) || !saved.contains("rarity", Tag.TAG_ANY_NUMERIC)) { rarity = 1; ignored++; }
                BlockPos anchor = saved.contains("anchor", Tag.TAG_LONG) ? BlockPos.of(saved.getLong("anchor")) : null;
                if (anchor == null || !accepted.contains(anchor)) {
                    anchor = accepted.stream().min(SpawnRegionTopology.POSITION_ORDER).orElseThrow();
                    ignored++;
                }
                previous.computeIfAbsent(dimension, key -> new ArrayList<>()).add(new Region(id, dimension, accepted, rarity, anchor, saved.contains("tier", Tag.TAG_STRING) ? safeTier(saved.getString("tier"), rarity) : LootTier.fromMultiplier(rarity)));
            }
            for (ResourceLocation dimension : data.dimensions().stream().sorted().toList()) {
                var result = SpawnRegionTopology.reconcile(dimension, data.floors(dimension), previous.getOrDefault(dimension, List.of()), data.nextRegionId);
                data.replaceRegions(dimension, result);
            }
        } else {
            ListTag dimensions = tag.getList("dimensions", Tag.TAG_COMPOUND);
            for (int i = 0; i < dimensions.size(); i++) {
                CompoundTag dimension = dimensions.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(dimension.getString("id"));
                if (id == null) { ignored++; continue; }
                for (long packed : dimension.getLongArray("floors")) {
                    BlockPos floor = BlockPos.of(packed);
                    if (data.contains(id, floor)) continue;
                    if (data.count(id) >= MAX_FLOORS_PER_DIMENSION || data.totalFloors >= MAX_TOTAL_FLOORS) { ignored++; continue; }
                    data.addUnchecked(id, floor);
                }
            }
            for (ResourceLocation dimension : data.dimensions().stream().sorted().toList()) {
                data.replaceRegions(dimension, SpawnRegionTopology.reconcile(dimension, data.floors(dimension), List.of(), data.nextRegionId));
            }
            data.setDirty(); // Write migrated metadata on the next ordinary save.
        }
        ListTag authors = tag.getList("visible_authors", Tag.TAG_STRING);
        for (int i = 0; i < authors.size(); i++) {
            try { data.visibleAuthors.add(UUID.fromString(authors.getString(i))); }
            catch (IllegalArgumentException invalidId) { ignored++; }
        }
        data.revision = Math.max(0, tag.getLong("revision"));
        if (ignored > 0) {
            LogUtils.getLogger().warn("Ignored or repaired {} invalid or over-limit spawn area records", ignored);
            data.setDirty();
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 3);
        tag.putLong("revision", revision);
        tag.putLong("next_region_id", nextRegionId);
        ListTag regions = new ListTag();
        for (Region region : regions()) {
            CompoundTag saved = new CompoundTag();
            saved.putLong("id", region.id());
            saved.putString("dimension", region.dimension().toString());
            saved.putDouble("rarity", region.rarity());
            saved.putString("tier", region.tier().id());
            saved.putLong("anchor", region.anchor().asLong());
            saved.putLongArray("floors", region.floors().stream().mapToLong(BlockPos::asLong).toArray());
            regions.add(saved);
        }
        tag.put("regions", regions);
        tag.remove("dimensions");
        ListTag authors = new ListTag();
        visibleAuthors.stream().map(UUID::toString).sorted().forEach(id -> authors.add(StringTag.valueOf(id)));
        tag.put("visible_authors", authors);
        return tag;
    }

    public long revision() { return revision; }
    public int totalCount() { return totalFloors; }
    public int count(ResourceLocation dimension) { return floors.getOrDefault(dimension, Set.of()).size(); }
    public boolean contains(ResourceLocation dimension, BlockPos pos) { return floors.getOrDefault(dimension, Set.of()).contains(pos); }
    public Set<BlockPos> floors(ResourceLocation dimension) { return Collections.unmodifiableSet(floors.getOrDefault(dimension, Set.of())); }
    public Set<ResourceLocation> dimensions() { return Set.copyOf(floors.keySet()); }
    public List<Region> regions() { return List.copyOf(regionById.values()); }
    public List<Region> regions(ResourceLocation dimension) { return regionById.values().stream().filter(region -> region.dimension().equals(dimension)).toList(); }
    @Nullable public Region region(long id) { return regionById.get(id); }
    @Nullable public Region regionAt(ResourceLocation dimension, BlockPos pos) {
        Long id = regionByFloor.getOrDefault(dimension, Map.of()).get(pos.asLong());
        return id == null ? null : regionById.get(id);
    }

    /** All coefficients and the geometry revision are checked before any coefficient is changed. */
    public void setRarities(long expectedRevision, Map<Long, Double> changes) {
        if (expectedRevision != revision) throw new IllegalStateException(Texts.tr("message.tacz_pubg.spawn_area_saved_data.loot_regions_have_changed_reopen_configuration"));
        if (changes == null) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.missing_region_rarities"));
        Map<Long, Double> checked = new HashMap<>();
        for (var entry : changes.entrySet()) {
            if (entry.getKey() == null || !regionById.containsKey(entry.getKey())) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.spawn_area_saved_data.loot_region_does_not_exist", entry.getKey()));
            if (entry.getValue() == null) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.missing_region_rarity"));
            validateRarity(entry.getValue());
            checked.put(entry.getKey(), entry.getValue());
        }
        boolean changed = checked.entrySet().stream().anyMatch(entry -> Double.compare(regionById.get(entry.getKey()).rarity(), entry.getValue()) != 0);
        if (!changed) return;
        for (var entry : checked.entrySet()) {
            Region old = regionById.get(entry.getKey());
            regionById.put(old.id(), new Region(old.id(), old.dimension(), old.floors(), entry.getValue(), old.anchor(), old.tier()));
        }
        markFloorsChanged(true);
    }

    public SpawnAreaGroups.GroupIndex groups(ResourceLocation dimension) {
        return groupCache.computeIfAbsent(dimension, id -> SpawnAreaGroups.compute(floors(id)));
    }

    public boolean showPreference(UUID player) { return visibleAuthors.contains(player); }
    public void setShowPreference(UUID player, boolean visible) {
        if (visible ? visibleAuthors.add(player) : visibleAuthors.remove(player)) setDirty();
    }

    /** Caller validates geometry before committing; topology and limits are validated atomically here. */
    private static LootTier safeTier(String id, double rarity) {
        try { return LootTier.fromId(id); } catch (IllegalArgumentException error) { return LootTier.fromMultiplier(rarity); }
    }

    public int add(ResourceLocation dimension, Collection<BlockPos> positions) {
        return add(dimension, positions, LootTier.HIGH);
    }
    public int add(ResourceLocation dimension, Collection<BlockPos> positions, LootTier tier) {
        Objects.requireNonNull(dimension, "dimension");
        if (owner != null && !positions.isEmpty()
                && com.example.examplemod.zones.MapZoneSavedData.get(owner).maps().stream().noneMatch(map ->
                    positions.stream().allMatch(pos -> MapSpawnBounds.contains(map, dimension, pos))))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.spawn_area_service.inside_map_required"));
        Set<BlockPos> unique = new HashSet<>();
        for (BlockPos pos : positions) unique.add(pos.immutable());
        unique.removeAll(floors.getOrDefault(dimension, Set.of()));
        if ((long) count(dimension) + unique.size() > MAX_FLOORS_PER_DIMENSION || (long) totalFloors + unique.size() > MAX_TOTAL_FLOORS) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.spawn_floor_limit_exceeded"));
        }
        if (unique.isEmpty()) return 0;
        Set<BlockPos> prospective = new HashSet<>(floors.getOrDefault(dimension, Set.of()));
        prospective.addAll(unique);
        var previous = new ArrayList<>(regions(dimension));
        previous.add(new Region(nextRegionId, dimension, new ArrayList<>(unique), tier.quantityMultiplier(),
                unique.stream().min(SpawnRegionTopology.POSITION_ORDER).orElseThrow(), tier));
        var result = SpawnRegionTopology.reconcile(dimension, prospective, previous, nextRegionId);
        for (BlockPos pos : unique) addUnchecked(dimension, pos);
        replaceRegions(dimension, result);
        groupCache.remove(dimension);
        markFloorsChanged(true);
        return unique.size();
    }

    public int remove(ResourceLocation dimension, Collection<BlockPos> positions) {
        Set<BlockPos> dimensionFloors = floors.get(dimension);
        if (dimensionFloors == null) return 0;
        Set<BlockPos> removed = new HashSet<>(positions);
        removed.retainAll(dimensionFloors);
        if (removed.isEmpty()) return 0;
        Set<BlockPos> prospective = new HashSet<>(dimensionFloors);
        prospective.removeAll(removed);
        var result = SpawnRegionTopology.reconcile(dimension, prospective, regions(dimension), nextRegionId);
        Map<Long, Set<BlockPos>> dimensionChunks = chunks.get(dimension);
        for (BlockPos pos : removed) {
            dimensionFloors.remove(pos);
            totalFloors--;
            long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            Set<BlockPos> chunkFloors = dimensionChunks.get(chunkKey);
            chunkFloors.remove(pos);
            if (chunkFloors.isEmpty()) dimensionChunks.remove(chunkKey);
        }
        if (dimensionFloors.isEmpty()) { floors.remove(dimension); chunks.remove(dimension); }
        replaceRegions(dimension, result);
        groupCache.remove(dimension);
        markFloorsChanged(true);
        return removed.size();
    }

    public int clear(ResourceLocation dimension) {
        Set<BlockPos> removed = floors.remove(dimension);
        chunks.remove(dimension);
        groupCache.remove(dimension);
        regionById.values().removeIf(region -> region.dimension().equals(dimension));
        regionByFloor.remove(dimension);
        int count = removed == null ? 0 : removed.size();
        totalFloors -= count;
        markFloorsChanged(count > 0);
        return count;
    }

    /** Uses only saved indices; never queries or loads terrain. */
    public List<BlockPos> nearby(ResourceLocation dimension, ChunkPos center, int radius) {
        Map<Long, Set<BlockPos>> dimensionChunks = chunks.get(dimension);
        if (dimensionChunks == null) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                result.addAll(dimensionChunks.getOrDefault(ChunkPos.asLong(x, z), Set.of()));
            }
        }
        result.sort(SpawnRegionTopology.POSITION_ORDER);
        return List.copyOf(result);
    }

    private long allocateRegionId() {
        if (nextRegionId == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.region_id_space_exhausted"));
        return nextRegionId++;
    }

    private void replaceRegions(ResourceLocation dimension, SpawnRegionTopology.Result result) {
        regionById.values().removeIf(region -> region.dimension().equals(dimension));
        Map<Long, Long> index = new HashMap<>();
        for (Region region : result.regions()) {
            regionById.put(region.id(), region);
            for (BlockPos floor : region.floors()) index.put(floor.asLong(), region.id());
        }
        if (index.isEmpty()) regionByFloor.remove(dimension);
        else regionByFloor.put(dimension, index);
        nextRegionId = result.nextRegionId();
    }

    private void addUnchecked(ResourceLocation dimension, BlockPos pos) {
        floors.computeIfAbsent(dimension, ignored -> new HashSet<>()).add(pos);
        chunks.computeIfAbsent(dimension, ignored -> new HashMap<>())
                .computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), ignored -> new HashSet<>()).add(pos);
        totalFloors++;
    }

    private static boolean validRarity(double rarity) { return Double.isFinite(rarity) && rarity >= 0 && rarity <= MAX_RARITY; }
    private static void validateRarity(double rarity) {
        if (!validRarity(rarity)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.spawn_area_saved_data.region_rarity_multiplier_finite_between", MAX_RARITY));
    }
    private void markFloorsChanged(boolean changed) {
        if (changed) { revision++; setDirty(); }
    }
}
