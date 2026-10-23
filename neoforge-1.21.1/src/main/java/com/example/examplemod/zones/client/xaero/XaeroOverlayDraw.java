package com.example.examplemod.zones.client.xaero;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.client.MapOverlayClient;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.List;

import static com.example.examplemod.zones.client.xaero.XaeroOverlayGeometry.*;

/** Same bounded drawing code for both native Xaero element renderers. */
final class XaeroOverlayDraw {
    private XaeroOverlayDraw() {}

    static void draw(GuiGraphics graphics, MapZoneModel.MapDefinition map, Projection projection,
            Viewport view, double depth, boolean worldMap) {
        if (map == null || !projection.valid() || view.halfWidth() <= 0 || view.halfHeight() <= 0) return;
        var polygon = List.of(projection.project(map.minX(), map.minZ()), projection.project(map.maxX(), map.minZ()),
                projection.project(map.maxX(), map.maxZ()), projection.project(map.minX(), map.maxZ()));
        if (polygon.stream().allMatch(p -> p.x() < -view.halfWidth())
                || polygon.stream().allMatch(p -> p.x() > view.halfWidth())
                || polygon.stream().allMatch(p -> p.y() < -view.halfHeight())
                || polygon.stream().allMatch(p -> p.y() > view.halfHeight())) return;
        graphics.flush();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = graphics.pose().last().pose();
        boolean runtime = !(worldMap && com.example.examplemod.zones.client.xaero.editor.NativeMapEditor.editing(map))
                && MapOverlayClient.runningFor(map);
        var frame = runtime ? MapOverlayClient.frameFor(map) : null;
        var safe = frame == null ? map.safeZone() : frame.current();
        var style = map.visualStyle();
        int blueFill = MapVisualStyle.argb(style.blueZoneRgb(), style.blueMapOpacity());
        int redFill = MapVisualStyle.argb(style.bombingZoneRgb(), style.bombingMapOpacity());
        // Raster slices stay bounded by the visible map size, even for a 60-million-block circle.
        double rowSize = Math.max(1, view.halfHeight() * 2 / (worldMap ? 640 : 192));
        for (double y = -view.halfHeight(); y < view.halfHeight(); y += rowSize) {
            double top = y, bottom = Math.min(view.halfHeight(), y + rowSize), middle = (top + bottom) * .5;
            Interval inside = polygonRow(polygon, middle).intersect(view.row(middle))
                    .intersect(polygonRow(polygon, top + .001)).intersect(polygonRow(polygon, bottom - .001));
            // Shrink to both strip edges so no pixels escape a round minimap's mask.
            if (view.circle()) inside = inside.intersect(view.row(top + .001)).intersect(view.row(bottom - .001));
            if (!inside.present()) continue;
            if (safe != null) {
                Point center = projection.project(safe.centerX(), safe.centerZ());
                // Subtract the widest circle slice within the strip; blue pixels never spill into the safe side.
                Interval hole = circleRow(center, safe.radius() * projection.scale(), Math.clamp(center.y(), top, bottom));
                if (hole.present()) {
                    strip(vertices, pose, new Interval(inside.min(), Math.min(inside.max(), hole.min())), top, bottom, depth, blueFill);
                    strip(vertices, pose, new Interval(Math.max(inside.min(), hole.max()), inside.max()), top, bottom, depth, blueFill);
                } else strip(vertices, pose, inside, top, bottom, depth, blueFill);
            }
        }
        var viewMask = viewportPolygon(view);
        for (var bombing : map.bombingZones()) {
            var area = bombing.area();
            double radius = area.radius() * projection.scale();
            int segments = Math.max(64, Math.min(512, (int) Math.ceil(Math.sqrt(Math.max(1, radius)) * 16)));
            var fill = clipConvex(circlePolygon(projection.project(area.centerX(), area.centerZ()), radius, segments), polygon);
            fill = clipConvex(fill, viewMask);
            // A clipped triangle fan covers every interior pixel, even a circle smaller than one raster row.
            for (int i = 1; i + 1 < fill.size(); i++) {
                Point a = fill.getFirst(), b = fill.get(i), c = fill.get(i + 1);
                quad(vertices, pose, a.x(), a.y(), b.x(), b.y(), c.x(), c.y(), c.x(), c.y(), depth + .04, redFill);
            }
        }
        for (int i = 0; i < polygon.size(); i++) screenLine(vertices, pose, polygon.get(i), polygon.get((i + 1) % 4), view, 1, depth + .1,
                MapVisualStyle.argb(style.mapBorderRgb(), runtime ? .35 : .8));
        var target = frame == null ? runtime ? MapOverlayClient.targetFor(map) : null : frame.target();
        if (safe != null) circle(vertices, pose, safe, map, projection, view, depth + .2,
                MapVisualStyle.argb(runtime ? style.blueZoneRgb() : style.safeZoneRgb(), 1));
        for (var bombing : map.bombingZones()) circle(vertices, pose, bombing.area(), map, projection, view, depth + .21,
                MapVisualStyle.argb(style.bombingZoneRgb(), 1));
        var path = map.flightPath();
        double flight = runtime ? MapOverlayClient.flightProgressFor(map) : -1;
        if (path != null) {
            // PUBG's deployment route alternates red and white; travelled segments become gray.
            double dx = path.endX() - path.startX(), dz = path.endZ() - path.startZ();
            double projectedLength = Math.hypot(dx, dz) * projection.scale();
            int segments = Math.max(2, Math.min(256, (int) Math.ceil(projectedLength / 8)));
            for (int i = 0; i < segments; i++) worldLine(vertices, pose,
                    new Point(path.startX() + dx * i / segments, path.startZ() + dz * i / segments),
                    new Point(path.startX() + dx * (i + 1) / segments, path.startZ() + dz * (i + 1) / segments),
                    map, projection, view, 1.5, depth + .3,
                    runtime && (i + .5) / segments < flight ? 0xB0CED3D8
                            : i % 2 == 0 ? MapVisualStyle.argb(style.flightPathRgb(), 1) : 0xFFF4F4F4);
            Point start = projection.project(path.startX(), path.startZ()), end = projection.project(path.endX(), path.endZ());
            double length = Math.hypot(end.x() - start.x(), end.y() - start.y());
            if (length > .001) {
                double ux = (end.x() - start.x()) / length, uy = (end.y() - start.y()) / length;
                screenLine(vertices, pose, end, new Point(end.x() - ux * 8 - uy * 4, end.y() - uy * 8 + ux * 4), view, 1.6, depth + .3,
                        0xFFF4F4F4);
                screenLine(vertices, pose, end, new Point(end.x() - ux * 8 + uy * 4, end.y() - uy * 8 - ux * 4), view, 1.6, depth + .3,
                        0xFFF4F4F4);
                if (flight >= 0) plane(vertices, pose,
                        new Point(start.x() + (end.x() - start.x()) * flight, start.y() + (end.y() - start.y()) * flight),
                        ux, uy, view, worldMap ? 8 : 6, depth + .42);
            }
        }
        // PUBG's destination circle is continuous, and remains visible before the first blue circle.
        if (target != null) {
            circle(vertices, pose, target, map, projection, view, depth + .4, 0xA0191D23, 2.8);
            circle(vertices, pose, target, map, projection, view, depth + .41,
                    MapVisualStyle.argb(style.safeZoneRgb(), 1), 1.3);
        }
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        var player = Minecraft.getInstance().player;
        var playerPosition = player == null ? null : player.getPosition(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        var destination = target != null ? target : safe;
        if (runtime && frame != null && com.example.examplemod.vitals.client.MatchVitalsHud.activeSession(frame.status().sessionId())
                && settings.enabled("map.navigationEnabled") && player != null && destination != null
                && player.level().dimension().location().equals(map.dimension()) && !destination.contains(playerPosition.x, playerPosition.z)) {
            int color = MapVisualStyle.argb(settings.color("map.navigationColor") & 0xFFFFFF, settings.number("map.navigationOpacity"));
            worldLine(vertices, pose, new Point(playerPosition.x, playerPosition.z),
                    new Point(destination.centerX(), destination.centerZ()), map, projection, view,
                    settings.number("map.navigationWidth"), depth + .43, color);
        }
        var teammates = MapOverlayClient.teammates();
        var localPlayer = Minecraft.getInstance().player;
        for (var member : teammates) {
            if (!member.mapMarkerVisible(map.dimension()) || localPlayer != null && member.id().equals(localPlayer.getUUID())
                    || !map.contains(member.x(), member.z())) continue;
            Point point = projection.project(member.x(), member.z());
            int color = 0xff000000 | (member.color() & 0xffffff);
            double radius = worldMap ? 5 : 4;
            if (!view.containsBox(point.x() - radius - 1, point.y() - radius - 1, point.x() + radius + 1, point.y() + radius + 1)) continue;
            disc(vertices, pose, point, radius + .7, depth + .49, 0xE0FFFFFF);
            disc(vertices, pose, point, radius, depth + .5, member.alive() ? color : 0xff777777);
        }
        graphics.bufferSource().endBatch(RenderType.gui());
        // Labels use the same native map pose, so they pan and zoom with their marker centers.
        var font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, depth + .6);
        for (var member : teammates) {
            if (!member.mapMarkerVisible(map.dimension()) || localPlayer != null && member.id().equals(localPlayer.getUUID())
                    || !map.contains(member.x(), member.z())) continue;
            Point point = projection.project(member.x(), member.z());
            double radius = worldMap ? 6 : 5;
            if (!view.containsBox(point.x() - radius, point.y() - radius, point.x() + radius, point.y() + radius)) continue;
            String number = Integer.toString(member.slot());
            int numberLeft = (int) Math.round(point.x() - font.width(number) / 2.0);
            int top = (int) point.y() - 4;
            if (view.containsBox(numberLeft, top, numberLeft + font.width(number), top + font.lineHeight))
                graphics.drawString(font, number, numberLeft, top, 0xffffffff, true);
            if (worldMap) {
                String label = member.name();
                int left = (int) point.x() + 8;
                if (view.containsBox(left, top, left + font.width(label) + 1, top + font.lineHeight + 1))
                    graphics.drawString(font, label, left, top, 0xffffffff, true);
            }
        }
        graphics.pose().popPose();
        graphics.flush();
    }

    private static void strip(VertexConsumer out, Matrix4f pose, Interval x, double top, double bottom, double depth, int color) {
        if (x.present()) quad(out, pose, x.min(), top, x.max(), top, x.max(), bottom, x.min(), bottom, depth, color);
    }
    private static void circle(VertexConsumer out, Matrix4f pose, MapZoneModel.Circle circle, MapZoneModel.MapDefinition map,
            Projection projection, Viewport view, double depth, int color) {
        circle(out, pose, circle, map, projection, view, depth, color, 1.5);
    }
    private static void circle(VertexConsumer out, Matrix4f pose, MapZoneModel.Circle circle, MapZoneModel.MapDefinition map,
            Projection projection, Viewport view, double depth, int color, double width) {
        for (var segment : circleOutline(new Point(circle.centerX(), circle.centerZ()), circle.radius(), projection, view))
            worldLine(out, pose, segment.start(), segment.end(), map, projection, view, width, depth, color);
    }
    private static void disc(VertexConsumer out, Matrix4f pose, Point center, double radius, double depth, int color) {
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16, b = Math.PI * 2 * (i + 1) / 16;
            quad(out, pose, center.x(), center.y(), center.x() + Math.cos(a) * radius, center.y() + Math.sin(a) * radius,
                    center.x() + Math.cos(b) * radius, center.y() + Math.sin(b) * radius, center.x(), center.y(), depth, color);
        }
    }
    private static void plane(VertexConsumer out, Matrix4f pose, Point center, double ux, double uy,
            Viewport view, double size, double depth) {
        // Geometry is native and clipped like every other map element; no copyrighted texture is bundled.
        Point nose = new Point(center.x() + ux * size, center.y() + uy * size);
        Point tail = new Point(center.x() - ux * size * .8, center.y() - uy * size * .8);
        Point wing = new Point(center.x() - ux * size * .1, center.y() - uy * size * .1);
        Point tailWing = new Point(center.x() - ux * size * .6, center.y() - uy * size * .6);
        for (double width : new double[] {3.4, 1.8}) {
            int color = width > 2 ? 0xC022262B : 0xFFFFFFFF;
            screenLine(out, pose, nose, tail, view, width, depth, color);
            screenLine(out, pose, new Point(wing.x() - uy * size, wing.y() + ux * size),
                    new Point(wing.x() + uy * size, wing.y() - ux * size), view, width, depth, color);
            screenLine(out, pose, new Point(tailWing.x() - uy * size * .4, tailWing.y() + ux * size * .4),
                    new Point(tailWing.x() + uy * size * .4, tailWing.y() - ux * size * .4), view, width, depth, color);
            depth += .01;
        }
    }
    private static void worldLine(VertexConsumer out, Matrix4f pose, Point a, Point b, MapZoneModel.MapDefinition map,
            Projection projection, Viewport view, double width, double depth, int color) {
        Point[] clipped = clipRectangle(a, b, map.minX(), map.minZ(), map.maxX(), map.maxZ());
        if (clipped != null) screenLine(out, pose, projection.project(clipped[0].x(), clipped[0].y()),
                projection.project(clipped[1].x(), clipped[1].y()), view, width, depth, color);
    }
    private static void screenLine(VertexConsumer out, Matrix4f pose, Point a, Point b, Viewport view, double width, double depth, int color) {
        // Inset clipping by half the stroke width keeps the entire stroke inside the minimap.
        Viewport inset = new Viewport(Math.max(.1, view.halfWidth() - width), Math.max(.1, view.halfHeight() - width), view.circle());
        Point[] clipped = clipViewport(a, b, inset); if (clipped == null) return;
        a = clipped[0]; b = clipped[1]; double length = Math.hypot(b.x() - a.x(), b.y() - a.y());
        if (length < .001) return;
        double nx = -(b.y() - a.y()) / length * width * .5, ny = (b.x() - a.x()) / length * width * .5;
        quad(out, pose, a.x() + nx, a.y() + ny, b.x() + nx, b.y() + ny,
                b.x() - nx, b.y() - ny, a.x() - nx, a.y() - ny, depth, color);
    }
    private static void quad(VertexConsumer out, Matrix4f pose, double x1, double y1, double x2, double y2,
            double x3, double y3, double x4, double y4, double depth, int color) {
        // RenderType.gui() uses CULL. Match GuiGraphics.fill's front-face winding for fills AND strokes.
        // The previous positive-winding strips were discarded; negative-winding outlines still appeared.
        out.addVertex(pose, (float) x1, (float) y1, (float) depth).setColor(color);
        if (needsGuiWindingReverse(x1, y1, x2, y2, x3, y3)) {
            out.addVertex(pose, (float) x4, (float) y4, (float) depth).setColor(color);
            out.addVertex(pose, (float) x3, (float) y3, (float) depth).setColor(color);
            out.addVertex(pose, (float) x2, (float) y2, (float) depth).setColor(color);
            return;
        }
        out.addVertex(pose, (float) x2, (float) y2, (float) depth).setColor(color);
        out.addVertex(pose, (float) x3, (float) y3, (float) depth).setColor(color);
        out.addVertex(pose, (float) x4, (float) y4, (float) depth).setColor(color);
    }
}
