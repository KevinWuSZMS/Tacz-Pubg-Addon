package com.example.examplemod.zones.client;

import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.MapZoneModel.Circle;
import com.example.examplemod.zones.MapZoneModel.MapDefinition;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

/** Native top view. Every fill is clipped to the rectangular map, with no world loading. */
final class MapZonePreview {
    private MapZonePreview() {}

    static void draw(GuiGraphics graphics, MapDefinition map, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF11171F);
        if (width < 8 || height < 8) return;
        double span = Math.max(map.maxX() - map.minX(), map.maxZ() - map.minZ());
        double unitWidth = (map.maxX() - map.minX()) / span, unitHeight = (map.maxZ() - map.minZ()) / span;
        double pixelsPerUnit = Math.min((width - 8.0) / unitWidth, (height - 8.0) / unitHeight);
        int mapWidth = Math.max(1, (int) Math.floor(unitWidth * pixelsPerUnit));
        int mapHeight = Math.max(1, (int) Math.floor(unitHeight * pixelsPerUnit));
        int left = x + (width - mapWidth) / 2, top = y + (height - mapHeight) / 2;
        MapVisualStyle style = map.visualStyle();
        graphics.fill(left, top, left + mapWidth, top + mapHeight, 0xFF41505C);
        if (map.safeZone() != null) {
            circleFill(graphics, map, map.safeZone(), left, top, mapWidth, mapHeight, span, pixelsPerUnit,
                    MapVisualStyle.argb(style.blueZoneRgb(), style.blueMapOpacity()), true);
            lightning(graphics, map, left, top, mapWidth, mapHeight, span, pixelsPerUnit, style);
            circleOutline(graphics, map, map.safeZone(), left, top, mapWidth, mapHeight,
                    span, pixelsPerUnit, 0xFF000000 | style.safeZoneRgb());
        }
        for (var zone : map.bombingZones()) {
            circleFill(graphics, map, zone.area(), left, top, mapWidth, mapHeight, span, pixelsPerUnit,
                    MapVisualStyle.argb(style.bombingZoneRgb(), style.bombingMapOpacity()), false);
            circleOutline(graphics, map, zone.area(), left, top, mapWidth, mapHeight,
                    span, pixelsPerUnit, 0xFF000000 | style.bombingZoneRgb());
        }
        if (map.flightPath() != null) {
            var path = map.flightPath();
            int sx = left + Math.clamp((int) Math.round((path.startX() - map.minX()) / span * pixelsPerUnit), 0, mapWidth - 1);
            int sz = top + Math.clamp((int) Math.round((path.startZ() - map.minZ()) / span * pixelsPerUnit), 0, mapHeight - 1);
            int ex = left + Math.clamp((int) Math.round((path.endX() - map.minX()) / span * pixelsPerUnit), 0, mapWidth - 1);
            int ez = top + Math.clamp((int) Math.round((path.endZ() - map.minZ()) / span * pixelsPerUnit), 0, mapHeight - 1);
            graphics.enableScissor(left, top, left + mapWidth, top + mapHeight);
            int color = 0xFF000000 | style.flightPathRgb();
            line(graphics, sx, sz, ex, ez, color);
            double length = Math.hypot(ex - sx, ez - sz);
            if (length > 0) {
                double ux = (ex - sx) / length, uz = (ez - sz) / length;
                line(graphics, ex, ez, (int) Math.round(ex - 7 * ux + 4 * uz),
                        (int) Math.round(ez - 7 * uz - 4 * ux), color);
                line(graphics, ex, ez, (int) Math.round(ex - 7 * ux - 4 * uz),
                        (int) Math.round(ez - 7 * uz + 4 * ux), color);
            }
            graphics.disableScissor();
        }
        int border = 0xFF000000 | style.mapBorderRgb();
        graphics.fill(left - 1, top - 1, left + mapWidth + 1, top, border);
        graphics.fill(left - 1, top + mapHeight, left + mapWidth + 1, top + mapHeight + 1, border);
        graphics.fill(left - 1, top, left, top + mapHeight, border);
        graphics.fill(left + mapWidth, top, left + mapWidth + 1, top + mapHeight, border);
    }

    private static void line(GuiGraphics graphics, int x1, int z1, int x2, int z2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0 : (double) step / steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
            graphics.fill(x, z, x + 1, z + 1, color);
        }
    }

    /** Sample pixel centers; a blue row subtracts the safe interval rather than tinting over it. */
    private static void circleFill(GuiGraphics graphics, MapDefinition map, Circle circle, int left, int top,
            int width, int height, double span, double pixelsPerUnit, int color, boolean outside) {
        double centerX = (circle.centerX() - map.minX()) / span * pixelsPerUnit;
        double centerZ = (circle.centerZ() - map.minZ()) / span * pixelsPerUnit;
        double radius = circle.radius() / span * pixelsPerUnit;
        for (int row = 0; row < height; row++) {
            double dz = row + 0.5 - centerZ;
            if (Math.abs(dz) > radius) {
                if (outside) graphics.fill(left, top + row, left + width, top + row + 1, color);
                continue;
            }
            double extent = Math.sqrt(Math.max(0, radius * radius - dz * dz));
            int from = (int) Math.clamp(Math.ceil(centerX - extent - 0.5), 0.0, width);
            int to = (int) Math.clamp(Math.floor(centerX + extent - 0.5) + 1, 0.0, width);
            if (outside) {
                if (from > 0) graphics.fill(left, top + row, left + from, top + row + 1, color);
                if (to < width) graphics.fill(left + to, top + row, left + width, top + row + 1, color);
            } else if (to > from) graphics.fill(left + from, top + row, left + to, top + row + 1, color);
        }
    }

    private static void circleOutline(GuiGraphics graphics, MapDefinition map, Circle circle, int left, int top,
            int width, int height, double span, double pixelsPerUnit, int color) {
        double cx = (circle.centerX() - map.minX()) / span * pixelsPerUnit;
        double cz = (circle.centerZ() - map.minZ()) / span * pixelsPerUnit;
        double radius = circle.radius() / span * pixelsPerUnit;
        // Scan both axes; work stays bounded by GUI size even when a circle extends millions of blocks past the map.
        for (int row = 0; row < height; row++) {
            double dz = row + 0.5 - cz;
            if (Math.abs(dz) > radius) continue;
            double extent = Math.sqrt(Math.max(0, radius * radius - dz * dz));
            point(graphics, left, top, width, height, cx - extent, row, color);
            point(graphics, left, top, width, height, cx + extent, row, color);
        }
        for (int column = 0; column < width; column++) {
            double dx = column + 0.5 - cx;
            if (Math.abs(dx) > radius) continue;
            double extent = Math.sqrt(Math.max(0, radius * radius - dx * dx));
            point(graphics, left, top, width, height, column, cz - extent, color);
            point(graphics, left, top, width, height, column, cz + extent, color);
        }
    }

    private static void point(GuiGraphics graphics, int left, int top, int width, int height,
            double x, double z, int color) {
        if (x < 0 || x >= width || z < 0 || z >= height) return;
        int px = (int) Math.floor(x), pz = (int) Math.floor(z);
        graphics.fill(left + px, top + pz, left + px + 1, top + pz + 1, color);
    }

    /** Small animated bolts are clipped pixel by pixel to blue territory, never to the safe interior. */
    private static void lightning(GuiGraphics graphics, MapDefinition map, int left, int top, int width, int height,
            double span, double pixelsPerUnit, MapVisualStyle style) {
        if (style.blueMapOpacity() <= 0) return;
        long phase = Util.getMillis() / 160;
        double scale = Math.min(1, Math.min(width, height) / 160.0);
        for (int bolt = 0; bolt < 24; bolt++) {
            if (Math.floorMod(phase + bolt * 3L, 11) > 3) continue;
            int bx = (int) Math.floor((bolt % 6 + 0.5) * width / 6.0);
            int bz = (int) Math.floor((bolt / 6 + 0.5) * height / 4.0);
            int priorX = bx, priorZ = bz;
            int color = MapVisualStyle.argb(style.lightningRgb(), Math.min(0.8, style.blueMapOpacity() * 1.7));
            for (int segment = 1; segment <= 5; segment++) {
                int nextX = bx + (int) Math.round(((segment % 2 == 0 ? -1 : 1) * (2 + Math.floorMod(bolt + phase, 4))) * scale);
                int nextZ = bz + (int) Math.round(segment * 3 * scale);
                int steps = Math.max(Math.abs(nextX - priorX), Math.abs(nextZ - priorZ));
                for (int step = 0; step <= steps; step++) {
                    double t = steps == 0 ? 0 : (double) step / steps;
                    int px = (int) Math.round(priorX + (nextX - priorX) * t);
                    int pz = (int) Math.round(priorZ + (nextZ - priorZ) * t);
                    if (px < 0 || px >= width || pz < 0 || pz >= height) continue;
                    double wx = map.minX() + (px + 0.5) / pixelsPerUnit * span;
                    double wz = map.minZ() + (pz + 0.5) / pixelsPerUnit * span;
                    if (!map.safeZone().contains(wx, wz)) graphics.fill(left + px, top + pz, left + px + 1, top + pz + 1, color);
                }
                priorX = nextX;
                priorZ = nextZ;
            }
        }
    }
}
