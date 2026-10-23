package com.example.examplemod.zones.client.xaero.editor;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.*;
import com.example.examplemod.zones.client.xaero.editor.NativeMapProjection.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Local, immutable-map transactions. No packets, SavedData or Xaero cache writes occur here. */
public final class NativeMapEditModel {
    public enum Tool {
        PAN(Texts.tr("screen.tacz_pubg.native_map_edit_model.pan")), SELECT(Texts.tr("screen.tacz_pubg.native_map_edit_model.select")), MAP(Texts.tr("screen.tacz_pubg.map_config.map")), SPAWN(Texts.tr("screen.tacz_pubg.map_staging.title")), SAFE(Texts.tr("screen.tacz_pubg.map_overlay_hud.safe_zone")), BOMB(Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone")), FLIGHT(Texts.tr("screen.tacz_pubg.zone_settings.flight_route"));
        private final String title;
        Tool(String title) { this.title = title; }
        public String title() { return title; }
    }
    public enum Kind { MAP, SPAWN, SAFE, BOMB, FLIGHT }
    /** Circle edge handles are on +X; rectangle handles are NW, NE, SE, SW. */
    public record Selection(Kind kind, int index) {}
    public record Handle(Point point, int index) {}
    private record Drag(MapDefinition before, Point start, Selection selected, int handle, boolean creating) {}
    private final MapDefinition initial;
    private final Deque<MapDefinition> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private MapDefinition draft;
    private Tool tool = Tool.PAN;
    private Selection selected;
    private Drag drag;
    private boolean zoneTools = true;
    public void zoneTools(boolean enabled) { zoneTools = enabled; }
    private String message = Texts.tr("screen.tacz_pubg.native_map_edit_model.select_tool_drag_terrain_scroll_zoom_use_pan");

    public NativeMapEditModel(MapDefinition initial) {
        if (!MapZoneModel.validate(initial).isEmpty()) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.native_map_edit_model.editor_requires_valid_map_draft"));
        this.initial = initial; this.draft = initial;
    }
    public MapDefinition draft() { return draft; }
    public Selection selected() { return selected; }
    public Tool tool() { return tool; }
    public String message() { return message; }
    public boolean dragging() { return drag != null; }
    public boolean dirty() { return !draft.equals(initial); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public void select(Kind kind, int index) {
        cancelDrag();
        boolean exists = switch (kind) {
            case MAP -> true;
            case SPAWN -> draft.stagingSpawn() != null;
            case SAFE -> draft.safeZone() != null;
            case BOMB -> index >= 0 && index < draft.bombingZones().size();
            case FLIGHT -> draft.flightPath() != null;
        };
        selected = exists ? new Selection(kind, kind == Kind.BOMB ? index : 0) : null;
        tool = Tool.SELECT;
        message = describeSelection();
    }
    /** Property edits use the same validation/history transaction as geometric drags. */
    public void properties(String name, double blueDamage) {
        cancelDrag();
        var old = draft;
        replaceValidated(new MapDefinition(old.id(), name, old.dimension(), old.minX(), old.minZ(), old.maxX(), old.maxZ(),
                old.safeZone(), blueDamage, old.bombingZones(), old.flightPath(), old.visualStyle(), old.stagingSpawn()));
        commit(old);
    }
    public void stagingRadius(double radius) {
        if (draft.stagingSpawn() == null) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.map_staging.tool_hint"));
        Circle old = draft.stagingSpawn();
        staging(new Circle(old.centerX(), old.centerZ(), radius));
    }
    private void staging(Circle spawn) {
        cancelDrag();
        MapDefinition old = draft;
        replaceValidated(withStaging(old, spawn));
        commit(old);
        selected = spawn == null ? null : new Selection(Kind.SPAWN, 0);
        message = Texts.tr("screen.tacz_pubg.map_staging.tool_hint");
    }
    public void bombingProperties(int index, int missilesPerMinute, double damage, double blastRadius) {
        cancelDrag();
        if (index < 0 || index >= draft.bombingZones().size()) throw new IllegalArgumentException(Texts.tr("screen.tacz_pubg.native_map_edit_model.select_bombardment_zone_first"));
        var old = draft;
        var zones = new ArrayList<>(old.bombingZones());
        var bomb = zones.get(index);
        zones.set(index, new BombingZone(bomb.id(), bomb.area(), missilesPerMinute, damage, blastRadius));
        replaceValidated(copy(old, old.safeZone(), zones, old.flightPath()));
        commit(old);
    }
    private void replaceValidated(MapDefinition candidate) {
        var errors = MapZoneModel.validate(candidate);
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
        MapZoneModel.toJson(candidate);
        draft = candidate;
        message = Texts.tr("screen.tacz_pubg.native_map_edit_model.properties_updated_local_draft_save_map_submit_them");
    }
    public void tool(Tool value) {
        if (!zoneTools && (value == Tool.SAFE || value == Tool.BOMB)) return;
        cancelDrag(); tool = value;
        message = switch (tool) {
            case PAN -> Texts.tr("screen.tacz_pubg.native_map_edit_model.left_drag_native_xaero_map_scroll_zoom_keys");
            case SELECT -> Texts.tr("screen.tacz_pubg.native_map_edit_model.select_circle_route_map_boundary_drag_move_square");
            case MAP -> Texts.tr("screen.tacz_pubg.native_map_edit_model.drag_rectangular_map_boundary_hold_shift_square");
            case SPAWN -> Texts.tr("screen.tacz_pubg.map_staging.tool_hint");
            case SAFE -> Texts.tr("screen.tacz_pubg.native_map_edit_model.press_inside_map_set_center_then_drag_out");
            case BOMB -> Texts.tr("screen.tacz_pubg.native_map_edit_model.press_inside_map_set_center_then_drag_out.tool");
            case FLIGHT -> Texts.tr("screen.tacz_pubg.native_map_edit_model.drag_directed_start_end_route_inside_map_replaces");
        };
    }

    /** False means empty selection: the screen may delegate the drag to native GuiMap navigation. */
    public boolean begin(Point point, double tolerance) {
        point = bounded(point);
        if (tool == Tool.PAN) return false;
        if (tool == Tool.SPAWN) {
            double edge = Math.min(Math.min(point.x() - draft.minX(), draft.maxX() - point.x()),
                    Math.min(point.z() - draft.minZ(), draft.maxZ() - point.z()));
            double radius = draft.stagingSpawn() == null ? Math.min(16, edge) : draft.stagingSpawn().radius();
            try { staging(new Circle(point.x(), point.z(), radius)); }
            catch (IllegalArgumentException error) { message = error.getMessage(); }
            return true; // Point placement has no drag/radius handle.
        }
        if (tool == Tool.SELECT) {
            int handle = -1;
            if (selected != null) for (var candidate : handles()) {
                if (distance(point, candidate.point) <= tolerance) { handle = candidate.index; break; }
            }
            if (handle < 0) selected = hit(point, tolerance);
            if (selected == null) { message = Texts.tr("screen.tacz_pubg.native_map_edit_model.drag_pan_map_click_object_select"); return false; }
            if (selected.kind() == Kind.SPAWN) { message = describeSelection(); return true; }
            drag = new Drag(draft, point, selected, handle, false); return true;
        }
        if (tool != Tool.MAP && !draft.contains(point.x(), point.z())) {
            message = Texts.tr("screen.tacz_pubg.native_map_edit_model.circle_centers_route_starts_inside_map_boundary"); return true;
        }
        if (tool == Tool.BOMB && draft.bombingZones().size() >= MapZoneModel.MAX_BOMBING_ZONES) {
            message = Texts.tr("screen.tacz_pubg.native_map_edit_model.each_map_allows_most_bombardment_zones_remove_existing"); return true;
        }
        selected = switch (tool) {
            case MAP -> new Selection(Kind.MAP, 0);
            case SAFE -> new Selection(Kind.SAFE, 0);
            case BOMB -> new Selection(Kind.BOMB, draft.bombingZones().size());
            case FLIGHT -> new Selection(Kind.FLIGHT, 0);
            default -> throw new IllegalStateException();
        };
        drag = new Drag(draft, point, selected, -1, true);
        update(point); return true;
    }

    public void update(Point point) { update(point, false); }
    public void update(Point point, boolean square) {
        if (drag == null) return;
        if (square && drag.selected.kind == Kind.MAP && (drag.creating || drag.handle >= 0)) {
            Point pivot = drag.creating ? drag.start : switch (drag.handle) {
                case 0 -> new Point(drag.before.maxX(), drag.before.maxZ());
                case 1 -> new Point(drag.before.minX(), drag.before.maxZ());
                case 2 -> new Point(drag.before.minX(), drag.before.minZ());
                default -> new Point(drag.before.maxX(), drag.before.minZ());
            };
            double dx = point.x() - pivot.x(), dz = point.z() - pivot.z();
            double side = Math.max(Math.abs(dx), Math.abs(dz));
            point = new Point(pivot.x() + Math.copySign(side, dx), pivot.z() + Math.copySign(side, dz));
        }
        point = bounded(point);
        MapDefinition before = drag.before;
        double dx = point.x() - drag.start.x(), dz = point.z() - drag.start.z();
        if (drag.creating) {
            switch (drag.selected.kind) {
                case MAP -> draft = bounds(before, Math.min(drag.start.x(), point.x()), Math.min(drag.start.z(), point.z()),
                        Math.max(drag.start.x(), point.x()), Math.max(drag.start.z(), point.z()));
                case SAFE -> draft = copy(before, new Circle(drag.start.x(), drag.start.z(), radius(drag.start, point)), before.bombingZones(), before.flightPath());
                case BOMB -> {
                    var zones = new ArrayList<>(before.bombingZones());
                    zones.add(new BombingZone(0, new Circle(drag.start.x(), drag.start.z(), radius(drag.start, point)),
                            MapZoneModel.DEFAULT_MISSILES_PER_MINUTE, MapZoneModel.DEFAULT_MISSILE_DAMAGE, MapZoneModel.DEFAULT_BLAST_RADIUS));
                    draft = copy(before, before.safeZone(), zones, before.flightPath());
                }
                case FLIGHT -> {
                    Point end = clamp(before, point);
                    draft = copy(before, before.safeZone(), before.bombingZones(),
                            new FlightPath(drag.start.x(), drag.start.z(), end.x(), end.z()));
                }
            }
        } else {
            switch (drag.selected.kind) {
                case MAP -> {
                    if (drag.handle < 0) draft = translateMap(before, dx, dz);
                    else {
                        double x1 = before.minX(), z1 = before.minZ(), x2 = before.maxX(), z2 = before.maxZ();
                        switch (drag.handle) {
                            case 0 -> { x1 = Math.min(point.x(), x2 - .01); z1 = Math.min(point.z(), z2 - .01); }
                            case 1 -> { x2 = Math.max(point.x(), x1 + .01); z1 = Math.min(point.z(), z2 - .01); }
                            case 2 -> { x2 = Math.max(point.x(), x1 + .01); z2 = Math.max(point.z(), z1 + .01); }
                            case 3 -> { x1 = Math.min(point.x(), x2 - .01); z2 = Math.max(point.z(), z1 + .01); }
                        }
                        draft = bounds(before, x1, z1, x2, z2);
                    }
                }
                case SAFE -> draft = copy(before, editCircle(before.safeZone(), before, point, dx, dz, drag.handle), before.bombingZones(), before.flightPath());
                case BOMB -> {
                    var zones = new ArrayList<>(before.bombingZones());
                    var old = zones.get(drag.selected.index);
                    zones.set(drag.selected.index, new BombingZone(old.id(), editCircle(old.area(), before, point, dx, dz, drag.handle),
                            old.missilesPerMinute(), old.missileDamage(), old.blastRadius()));
                    draft = copy(before, before.safeZone(), zones, before.flightPath());
                }
                case FLIGHT -> {
                    var old = before.flightPath();
                    if (drag.handle >= 0) {
                        Point end = clamp(before, point);
                        draft = copy(before, before.safeZone(), before.bombingZones(), drag.handle == 0
                                ? new FlightPath(end.x(), end.z(), old.endX(), old.endZ())
                                : new FlightPath(old.startX(), old.startZ(), end.x(), end.z()));
                    } else {
                        dx = Math.clamp(dx, before.minX() - Math.min(old.startX(), old.endX()), before.maxX() - Math.max(old.startX(), old.endX()));
                        dz = Math.clamp(dz, before.minZ() - Math.min(old.startZ(), old.endZ()), before.maxZ() - Math.max(old.startZ(), old.endZ()));
                        draft = copy(before, before.safeZone(), before.bombingZones(),
                                new FlightPath(old.startX() + dx, old.startZ() + dz, old.endX() + dx, old.endZ() + dz));
                    }
                }
            }
        }
        var errors = MapZoneModel.validate(draft);
        message = errors.isEmpty() ? describeSelection() : Texts.tr("screen.tacz_pubg.native_map_edit_model.releasing_will_cancel", errors.getFirst());
    }

    public boolean finish(Point point) { return finish(point, false); }
    public boolean finish(Point point, boolean square) {
        if (drag == null) return false;
        update(point, square);
        var old = drag.before;
        var errors = MapZoneModel.validate(draft);
        drag = null;
        if (!errors.isEmpty()) { draft = old; selected = null; message = Texts.tr("screen.tacz_pubg.native_map_edit_model.cancelled", errors.getFirst()); return false; }
        commit(old); message = describeSelection(); return true;
    }
    public boolean cancelDrag() {
        if (drag == null) return false;
        draft = drag.before; drag = null; selected = null; message = Texts.tr("screen.tacz_pubg.native_map_edit_model.current_drag_cancelled_original_draft_retained"); return true;
    }
    public boolean deleteSelection() {
        cancelDrag();
        if (selected == null) { message = Texts.tr("screen.tacz_pubg.native_map_edit_model.select_object_select_tool_first"); return false; }
        var old = draft;
        switch (selected.kind) {
            case MAP -> { message = Texts.tr("screen.tacz_pubg.native_map_edit_model.confirm_map_deletion_configuration_screen"); return false; }
            case SPAWN -> draft = withStaging(draft, null);
            case SAFE -> draft = copy(draft, null, draft.bombingZones(), draft.flightPath());
            case FLIGHT -> draft = copy(draft, draft.safeZone(), draft.bombingZones(), null);
            case BOMB -> {
                var zones = new ArrayList<>(draft.bombingZones()); zones.remove(selected.index);
                draft = copy(draft, draft.safeZone(), zones, draft.flightPath());
            }
        }
        commit(old); selected = null; message = Texts.tr("screen.tacz_pubg.native_map_edit_model.removed_draft_ctrl_z_undo"); return true;
    }
    public void undo() {
        cancelDrag(); if (undo.isEmpty()) return;
        redo.push(draft); draft = undo.pop(); selected = null; message = Texts.tr("screen.tacz_pubg.native_map_edit_model.last_edit_undone");
    }
    public void redo() {
        cancelDrag(); if (redo.isEmpty()) return;
        undo.push(draft); draft = redo.pop(); selected = null; message = Texts.tr("screen.tacz_pubg.native_map_edit_model.edit_redone");
    }
    private void commit(MapDefinition before) {
        if (draft.equals(before)) return;
        undo.push(before); if (undo.size() > 64) undo.removeLast(); redo.clear();
    }

    public List<Handle> handles() {
        if (selected == null) return List.of();
        return switch (selected.kind) {
            case MAP -> List.of(new Handle(new Point(draft.minX(), draft.minZ()), 0), new Handle(new Point(draft.maxX(), draft.minZ()), 1),
                    new Handle(new Point(draft.maxX(), draft.maxZ()), 2), new Handle(new Point(draft.minX(), draft.maxZ()), 3));
            case SPAWN -> List.of();
            case SAFE -> circleHandles(draft.safeZone());
            case BOMB -> selected.index >= draft.bombingZones().size() ? List.of() : circleHandles(draft.bombingZones().get(selected.index).area());
            case FLIGHT -> draft.flightPath() == null ? List.of() : List.of(
                    new Handle(new Point(draft.flightPath().startX(), draft.flightPath().startZ()), 0),
                    new Handle(new Point(draft.flightPath().endX(), draft.flightPath().endZ()), 1));
        };
    }
    private static List<Handle> circleHandles(Circle circle) {
        return circle == null ? List.of() : List.of(new Handle(new Point(circle.centerX(), circle.centerZ()), 0),
                new Handle(new Point(circle.centerX() + circle.radius(), circle.centerZ()), 1));
    }

    private Selection hit(Point p, double t) {
        Circle spawn = draft.stagingSpawn();
        if (spawn != null && (spawn.contains(p.x(), p.z())
                || Math.abs(distance(p, new Point(spawn.centerX(), spawn.centerZ())) - spawn.radius()) <= t))
            return new Selection(Kind.SPAWN, 0);
        // Border beats fill, and the smallest containing circle wins overlapping interiors.
        if (draft.flightPath() != null && segmentDistance(p, new Point(draft.flightPath().startX(), draft.flightPath().startZ()),
                new Point(draft.flightPath().endX(), draft.flightPath().endZ())) <= t) return new Selection(Kind.FLIGHT, 0);
        Selection nearest = null; double size = Double.POSITIVE_INFINITY;
        for (int i = 0; zoneTools && i < draft.bombingZones().size(); i++) {
            Circle c = draft.bombingZones().get(i).area();
            if (Math.abs(distance(p, new Point(c.centerX(), c.centerZ())) - c.radius()) <= t) return new Selection(Kind.BOMB, i);
            if (c.contains(p.x(), p.z()) && c.radius() < size) { nearest = new Selection(Kind.BOMB, i); size = c.radius(); }
        }
        Circle safe = draft.safeZone();
        if (zoneTools && safe != null) {
            if (Math.abs(distance(p, new Point(safe.centerX(), safe.centerZ())) - safe.radius()) <= t) return new Selection(Kind.SAFE, 0);
            if (safe.contains(p.x(), p.z()) && safe.radius() < size) nearest = new Selection(Kind.SAFE, 0);
        }
        if (nearest != null) return nearest;
        var corners = List.of(new Point(draft.minX(), draft.minZ()), new Point(draft.maxX(), draft.minZ()),
                new Point(draft.maxX(), draft.maxZ()), new Point(draft.minX(), draft.maxZ()));
        for (int i = 0; i < 4; i++) if (segmentDistance(p, corners.get(i), corners.get((i + 1) % 4)) <= t) return new Selection(Kind.MAP, 0);
        return null;
    }
    public String describeSelection() {
        if (selected == null) return Texts.tr("screen.tacz_pubg.native_map_edit_model.select_map_object");
        return switch (selected.kind) {
            case MAP -> Texts.tr("screen.tacz_pubg.native_map_edit_model.map", draft.id(), n(draft.minX()), n(draft.minZ()), n(draft.maxX()), n(draft.maxZ()));
            case SPAWN -> draft.stagingSpawn() == null ? Texts.tr("screen.tacz_pubg.map_staging.title")
                    : circleText(Texts.tr("screen.tacz_pubg.map_staging.title"), draft.stagingSpawn());
            case SAFE -> draft.safeZone() == null ? Texts.tr("screen.tacz_pubg.map_overlay_hud.safe_zone") : circleText(Texts.tr("screen.tacz_pubg.map_overlay_hud.safe_zone"), draft.safeZone());
            case BOMB -> selected.index >= draft.bombingZones().size() ? Texts.tr("screen.tacz_pubg.zone_settings.bombardment_zone") : circleText(Texts.tr("screen.tacz_pubg.native_map_edit_model.bombardment_zone", (selected.index + 1)), draft.bombingZones().get(selected.index).area());
            case FLIGHT -> draft.flightPath() == null ? Texts.tr("screen.tacz_pubg.zone_settings.flight_route") : Texts.tr("screen.tacz_pubg.native_map_edit_model.route", n(draft.flightPath().startX()), n(draft.flightPath().startZ()), n(draft.flightPath().endX()), n(draft.flightPath().endZ()));
        };
    }
    private static String circleText(String name, Circle c) { return name + Texts.tr("screen.tacz_pubg.native_map_edit_model.center") + n(c.centerX()) + ", " + n(c.centerZ()) + Texts.tr("screen.tacz_pubg.native_map_edit_model.radius") + n(c.radius()); }
    private static String n(double value) { return String.format(java.util.Locale.ROOT, "%.1f", value); }
    private static Circle editCircle(Circle c, MapDefinition map, Point p, double dx, double dz, int handle) {
        if (handle == 1) return new Circle(c.centerX(), c.centerZ(), radius(new Point(c.centerX(), c.centerZ()), p));
        Point center = clamp(map, new Point(c.centerX() + dx, c.centerZ() + dz));
        return new Circle(center.x(), center.z(), c.radius());
    }
    private static MapDefinition translateMap(MapDefinition old, double dx, double dz) {
        dx = Math.clamp(dx, -MapZoneModel.MAX_COORDINATE - old.minX(), MapZoneModel.MAX_COORDINATE - old.maxX());
        dz = Math.clamp(dz, -MapZoneModel.MAX_COORDINATE - old.minZ(), MapZoneModel.MAX_COORDINATE - old.maxZ());
        final double x = dx, z = dz;
        var circles = old.bombingZones().stream().map(b -> new BombingZone(b.id(), shifted(b.area(), x, z),
                b.missilesPerMinute(), b.missileDamage(), b.blastRadius())).toList();
        var f = old.flightPath();
        return new MapDefinition(old.id(), old.name(), old.dimension(), old.minX() + x, old.minZ() + z,
                old.maxX() + x, old.maxZ() + z, shifted(old.safeZone(), x, z), old.blueDamagePerSecond(), circles,
                f == null ? null : new FlightPath(f.startX() + x, f.startZ() + z, f.endX() + x, f.endZ() + z), old.visualStyle(),
                shifted(old.stagingSpawn(), x, z));
    }
    private static Circle shifted(Circle c, double dx, double dz) { return c == null ? null : new Circle(c.centerX() + dx, c.centerZ() + dz, c.radius()); }
    private static MapDefinition copy(MapDefinition m, Circle s, List<BombingZone> b, FlightPath f) {
        return new MapDefinition(m.id(), m.name(), m.dimension(), m.minX(), m.minZ(), m.maxX(), m.maxZ(), s, m.blueDamagePerSecond(), b, f, m.visualStyle(), m.stagingSpawn());
    }
    private static MapDefinition bounds(MapDefinition m, double x1, double z1, double x2, double z2) {
        return new MapDefinition(m.id(), m.name(), m.dimension(), x1, z1, x2, z2, m.safeZone(), m.blueDamagePerSecond(), m.bombingZones(), m.flightPath(), m.visualStyle(), m.stagingSpawn());
    }
    private static MapDefinition withStaging(MapDefinition m, Circle spawn) {
        return new MapDefinition(m.id(), m.name(), m.dimension(), m.minX(), m.minZ(), m.maxX(), m.maxZ(),
                m.safeZone(), m.blueDamagePerSecond(), m.bombingZones(), m.flightPath(), m.visualStyle(), spawn);
    }
    private static Point bounded(Point p) {
        return new Point(Math.clamp(p.x(), -MapZoneModel.MAX_COORDINATE, MapZoneModel.MAX_COORDINATE),
                Math.clamp(p.z(), -MapZoneModel.MAX_COORDINATE, MapZoneModel.MAX_COORDINATE));
    }
    private static Point clamp(MapDefinition m, Point p) { return new Point(Math.clamp(p.x(), m.minX(), m.maxX()), Math.clamp(p.z(), m.minZ(), m.maxZ())); }
    private static double radius(Point a, Point b) { return Math.clamp(distance(a, b), .1, MapZoneModel.MAX_RADIUS); }
    private static double distance(Point a, Point b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()); }
    private static double segmentDistance(Point p, Point a, Point b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.clamp(((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / length, 0, 1);
        return distance(p, new Point(a.x() + dx * t, a.z() + dz * t));
    }
}
