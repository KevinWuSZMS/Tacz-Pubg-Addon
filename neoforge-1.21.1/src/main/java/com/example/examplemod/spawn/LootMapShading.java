package com.example.examplemod.spawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.lootconfig.LootTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/** Distinct Y floors share an X/Z column; colour darkens linearly until n storeys is black. */
public final class LootMapShading {
    private LootMapShading() {}
    public record Column(int x, int z, Map<LootTier, Integer> counts) {
        public Column { counts = Map.copyOf(counts); }
        public int layers() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    }
    public static List<Column> columns(Map<Long, LootTier> floors) {
        Map<Long, Map<LootTier, Integer>> columns = new HashMap<>();
        floors.forEach((packed, tier) -> {
            BlockPos pos = BlockPos.of(packed);
            columns.computeIfAbsent(ChunkPos.asLong(pos.getX(), pos.getZ()), ignored -> new HashMap<>()).merge(tier, 1, Integer::sum);
        });
        return columns.entrySet().stream().map(entry -> new Column(ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey()), entry.getValue())).toList();
    }
    public static int color(Column column, Map<String, Integer> palette, int blackLayers) {
        int layers = column.layers();
        if (layers < 1 || blackLayers < 2) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.loot_map_shading.invalid_loot_overlay_depth"));
        double light = Math.max(0, 1 - (layers - 1d) / (blackLayers - 1d));
        double red = 0, green = 0, blue = 0;
        for (var entry : column.counts().entrySet()) {
            int rgb = palette.get(entry.getKey().id()), count = entry.getValue();
            red += ((rgb >> 16) & 255) * count; green += ((rgb >> 8) & 255) * count; blue += (rgb & 255) * count;
        }
        int alpha = (int) Math.round(255 - 71 * light);
        return (alpha << 24) | ((int) Math.round(red / layers * light) << 16)
                | ((int) Math.round(green / layers * light) << 8) | (int) Math.round(blue / layers * light);
    }
}
