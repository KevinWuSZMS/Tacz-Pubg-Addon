package com.example.examplemod.zones.client.xaero;

import com.example.examplemod.lootconfig.LootTier;
import com.example.examplemod.spawn.LootMapShading;
import com.example.examplemod.spawn.SpawnAreaService;
import com.example.examplemod.spawn.client.SpawnAreaClientState;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import static com.example.examplemod.zones.client.xaero.XaeroOverlayGeometry.*;

/** All loot tiers share the editor's colour calculation and one native-map rendering path. */
final class XaeroLootOverlay {
    private record Palette(long id, double minX, double minZ, double maxX, double maxZ,
            Map<String, Integer> colors, int blackLayers) {
        boolean contains(int x, int z) {
            return x >= minX && x < maxX && z >= minZ && z < maxZ;
        }
    }
    private record Cell(int x, int z, int color) {}
    private static Map<Long, LootTier> cachedFloors;
    private static Map<String, Integer> cachedPalette;
    private static int cachedLayers;
    private static List<Palette> cachedMaps = List.of();
    private static Map<Long, List<Cell>> chunks = Map.of();

    private XaeroLootOverlay() {}

    static boolean holdingFlag() {
        var player = Minecraft.getInstance().player;
        return player != null && SpawnAreaService.holdsTool(player);
    }

    static void draw(GuiGraphics graphics, ResourceLocation dimension, List<MapDefinition> maps,
            Projection projection, Viewport view, double depth) {
        if (!holdingFlag() || !projection.valid() || view.halfWidth() <= 0 || view.halfHeight() <= 0) return;
        var floors = SpawnAreaClientState.mapFloors(dimension);
        var palette = SpawnAreaClientState.lootPalette();
        int layers = SpawnAreaClientState.overlapBlackLayers();
        var styles = SpawnAreaClientState.globalLootPalette() ? List.<Palette>of() : maps.stream()
                .filter(map -> map.dimension().equals(dimension))
                .sorted(Comparator.comparingLong(MapDefinition::id).reversed())
                .map(map -> new Palette(map.id(), map.minX(), map.minZ(), map.maxX(), map.maxZ(),
                        map.visualStyle().lootTierRgb(), map.visualStyle().lootOverlapBlackLayers())).toList();
        // Ignore live circle/flight progress. Rebuild only when floors or their actual colours change.
        if (cachedFloors != floors || !palette.equals(cachedPalette) || cachedLayers != layers || !styles.equals(cachedMaps)) {
            var replacement = new HashMap<Long, List<Cell>>();
            for (var column : LootMapShading.columns(floors)) {
                var colors = palette;
                int blackLayers = layers;
                for (var style : styles) if (style.contains(column.x(), column.z())) {
                    colors = style.colors(); blackLayers = style.blackLayers(); break;
                }
                replacement.computeIfAbsent(ChunkPos.asLong(column.x() >> 4, column.z() >> 4), ignored -> new ArrayList<>())
                        .add(new Cell(column.x(), column.z(), LootMapShading.color(column, colors, blackLayers)));
            }
            chunks = replacement;
            cachedFloors = floors; cachedPalette = palette; cachedLayers = layers; cachedMaps = styles;
        }
        if (chunks.isEmpty()) return;
        var corners = List.of(new Point(-view.halfWidth(), -view.halfHeight()), new Point(view.halfWidth(), -view.halfHeight()),
                new Point(view.halfWidth(), view.halfHeight()), new Point(-view.halfWidth(), view.halfHeight()));
        var world = corners.stream().map(projection::unproject).toList();
        double minX = world.stream().mapToDouble(Point::x).min().orElse(0), maxX = world.stream().mapToDouble(Point::x).max().orElse(0);
        double minZ = world.stream().mapToDouble(Point::y).min().orElse(0), maxZ = world.stream().mapToDouble(Point::y).max().orElse(0);
        var mask = viewportPolygon(view);
        graphics.flush();
        var vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        var pose = graphics.pose().last().pose();
        for (var chunk : chunks.entrySet()) {
            int x = ChunkPos.getX(chunk.getKey()) * 16, z = ChunkPos.getZ(chunk.getKey()) * 16;
            if (x + 16 < minX || x > maxX || z + 16 < minZ || z > maxZ) continue;
            for (var cell : chunk.getValue()) {
                if (cell.x() + 1 < minX || cell.x() > maxX || cell.z() + 1 < minZ || cell.z() > maxZ) continue;
                var polygon = List.of(projection.project(cell.x(), cell.z()), projection.project(cell.x() + 1, cell.z()),
                        projection.project(cell.x() + 1, cell.z() + 1), projection.project(cell.x(), cell.z() + 1));
                if (!polygon.stream().allMatch(view::contains)) polygon = clipConvex(polygon, mask);
                // One fill per X/Z column: overlapping maps must not compound alpha or floor counts.
                for (int i = 1; i + 1 < polygon.size(); i++)
                    triangle(vertices, pose, polygon.getFirst(), polygon.get(i), polygon.get(i + 1), depth, cell.color());
            }
        }
        graphics.bufferSource().endBatch(RenderType.gui());
    }

    private static void triangle(VertexConsumer out, Matrix4f pose, Point a, Point b, Point c, double depth, int color) {
        if (needsGuiWindingReverse(a.x(), a.y(), b.x(), b.y(), c.x(), c.y())) {
            Point swap = b; b = c; c = swap;
        }
        out.addVertex(pose, (float) a.x(), (float) a.y(), (float) depth).setColor(color);
        out.addVertex(pose, (float) b.x(), (float) b.y(), (float) depth).setColor(color);
        out.addVertex(pose, (float) c.x(), (float) c.y(), (float) depth).setColor(color);
        out.addVertex(pose, (float) c.x(), (float) c.y(), (float) depth).setColor(color);
    }
}
