package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import net.minecraft.core.BlockPos;
import com.example.examplemod.lootconfig.LootTier;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure reconciliation of approved floors; never reads terrain or invents floor cells. */
public final class SpawnRegionTopology {
    public static final Comparator<BlockPos> POSITION_ORDER = Comparator.comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);

    private SpawnRegionTopology() {}

    public record Result(List<SpawnAreaSavedData.Region> regions, long nextRegionId) {
        public Result { regions = List.copyOf(regions); }
    }

    /**
     * Retains an old identity on its anchor's component (or its first remaining cell).
     * Several retained identities merging into a component select the oldest ID.
     * Other split components receive fresh IDs and inherit their previous rarity.
     */
    public static Result reconcile(ResourceLocation dimension, Collection<BlockPos> marked,
                                   List<SpawnAreaSavedData.Region> previous, long nextRegionId) {
        if (nextRegionId < 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_region_topology.invalid_next_region_id"));
        // Adjacent markers of different tiers remain separate authoring regions.
        Map<BlockPos, LootTier> tiers = new HashMap<>();
        for (var region : previous) for (var floor : region.floors()) tiers.put(floor, region.tier());
        List<List<BlockPos>> components = new ArrayList<>();
        for (LootTier tier : LootTier.values()) {
            var grouping = SpawnAreaGroups.compute(marked.stream().filter(pos -> tiers.getOrDefault(pos, LootTier.HIGH) == tier).toList());
            Map<Long, List<BlockPos>> byComponent = new HashMap<>();
            for (var entry : grouping.groupByFloor().entrySet())
                byComponent.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(BlockPos.of(entry.getKey()));
            components.addAll(byComponent.values());
        }
        components.forEach(component -> component.sort(POSITION_ORDER));
        components.sort((a, b) -> POSITION_ORDER.compare(a.getFirst(), b.getFirst()));
        Map<BlockPos, Integer> componentByFloor = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (BlockPos floor : components.get(i)) componentByFloor.put(floor, i);
        }
        Map<Integer, SpawnAreaSavedData.Region> retained = new HashMap<>();
        Map<Integer, SpawnAreaSavedData.Region> inherited = new HashMap<>();
        for (SpawnAreaSavedData.Region old : previous) {
            if (!old.dimension().equals(dimension)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.spawn_region_topology.region_dimension_mismatch"));
            Integer preferred = componentByFloor.get(old.anchor());
            for (BlockPos floor : old.floors()) {
                Integer index = componentByFloor.get(floor);
                if (index == null) continue;
                inherited.merge(index, old, SpawnRegionTopology::oldest);
                // floors are sorted, so this fallback is deterministic if the anchor was deleted.
                if (preferred == null) preferred = index;
            }
            if (preferred != null) retained.merge(preferred, old, SpawnRegionTopology::oldest);
        }
        Map<Long, SpawnAreaSavedData.Region> output = new TreeMap<>();
        for (int i = 0; i < components.size(); i++) {
            List<BlockPos> component = components.get(i);
            SpawnAreaSavedData.Region keep = retained.get(i);
            long id;
            double rarity;
            BlockPos anchor = component.getFirst();
            if (keep != null) {
                id = keep.id();
                nextRegionId = Math.max(nextRegionId, id + 1);
                rarity = keep.rarity();
                if (componentByFloor.getOrDefault(keep.anchor(), -1) == i) anchor = keep.anchor();
            } else {
                if (nextRegionId == Long.MAX_VALUE) throw new IllegalStateException(Texts.tr("error.tacz_pubg.spawn_area_saved_data.region_id_space_exhausted"));
                id = nextRegionId++;
                SpawnAreaSavedData.Region source = inherited.get(i);
                rarity = source == null ? 1 : source.rarity();
            }
            output.put(id, new SpawnAreaSavedData.Region(id, dimension, component, rarity, anchor, tiers.getOrDefault(component.getFirst(), LootTier.HIGH)));
        }
        return new Result(new ArrayList<>(output.values()), nextRegionId);
    }

    private static SpawnAreaSavedData.Region oldest(SpawnAreaSavedData.Region a, SpawnAreaSavedData.Region b) {
        return a.id() < b.id() ? a : b;
    }
}
