package com.example.examplemod.zones.client.xaero.editor;

import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.MapZoneModel;
import com.example.examplemod.zones.MapZoneModel.*;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditModel.Kind;
import com.example.examplemod.zones.client.xaero.editor.NativeMapEditModel.Tool;
import com.example.examplemod.zones.client.xaero.editor.NativeMapProjection.Point;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Random;

/** Geometry, editing transactions and projection checks only; no game, screen, graphics or world is started. */
public final class NativeMapEditorChecks {
    private static int checks;
    public static void main(String[] args) {
        squareAndNormalTools();
        projectionRoundTrips(); stagingMarkerZoomAndGuiScale(); creationAndUndo(); movementAndHandles(); rejectedBoundsKeepObjects();
        deleteOnlyDraft(); cancellingAndHistory(); boundsAndLimits(); propertyTransactions(); compactViewport();
        System.out.println("Native Xaero map editor checks passed: " + checks);
    }
    private static void squareAndNormalTools() {
        var model = new NativeMapEditModel(base());
        model.zoneTools(false);
        model.tool(Tool.SAFE); check(model.tool() != Tool.SAFE, "ordinary mode cannot activate safety tool");
        model.tool(Tool.BOMB); check(model.tool() != Tool.BOMB, "ordinary mode cannot activate bombing tool");
        model.tool(Tool.MAP); model.begin(p(5, 10), 1); model.finish(p(45, 30), true);
        near(model.draft().maxX() - model.draft().minX(), 40, 1e-8, "Shift retains longest drag axis");
        near(model.draft().maxZ() - model.draft().minZ(), 40, 1e-8, "Shift creates square in a distinct Y-free map plane");
        model.tool(Tool.FLIGHT); model.begin(p(10, 15), 1); model.finish(p(30, 35));
        check(model.draft().flightPath() != null, "flight tool remains available in ordinary mode");
        model.select(Kind.MAP, 0); model.begin(p(45, 50), 1); model.finish(p(65, 55), true);
        near(model.draft().maxX() - model.draft().minX(), model.draft().maxZ() - model.draft().minZ(), 1e-8, "Shift corner resize keeps map square");
    }

    private static MapDefinition base() {
        return new MapDefinition(7, "测试地图", ResourceLocation.parse("minecraft:overworld"), 0, 0, 100, 100,
                null, 2.5, List.of(), null, MapVisualStyle.DEFAULT);
    }
    private static void projectionRoundTrips() {
        var random = new Random(314159);
        for (int i = 0; i < 15_000; i++) {
            int rawW = 1280 + random.nextInt(2200), rawH = 720 + random.nextInt(1500), scale = 1 + random.nextInt(5);
            var p = new NativeMapProjection(random.nextDouble() * 58_000_000 - 29_000_000,
                    random.nextDouble() * 58_000_000 - 29_000_000, Math.pow(2, random.nextDouble() * 10 - 4),
                    rawW, rawH, (int) Math.ceil(rawW / (double) scale), (int) Math.ceil(rawH / (double) scale));
            check(p.valid(), "valid native projection");
            double x = p.cameraX() + random.nextDouble() * 800 - 400, z = p.cameraZ() + random.nextDouble() * 800 - 400;
            var screen = p.screen(x, z); var world = p.world(screen.x(), screen.z());
            near(world.x(), x, 1e-7, "X world/gui roundtrip"); near(world.z(), z, 1e-7, "Z world/gui roundtrip");
            var screen2 = p.screen(p.world(101.7, 212.3).x(), p.world(101.7, 212.3).z());
            near(screen2.x(), 101.7, 1e-5, "GUI X inverse"); near(screen2.z(), 212.3, 1e-5, "GUI Y inverse");
        }
        check(!new NativeMapProjection(0, 0, 0, 100, 100, 100, 100).valid(), "reject zero scale");
        check(!new NativeMapProjection(Double.NaN, 0, 1, 100, 100, 100, 100).valid(), "reject nan camera");
    }
    private static void stagingMarkerZoomAndGuiScale() {
        // Explicit expected values catch a fixed-radius regression as well as incorrect clamp/round order.
        double[] zooms = {Double.MIN_VALUE, .125, .5, 1,
                Math.nextDown(1.25), 1.25, 1.5, Math.nextDown(1.75), 1.75, 2,
                Math.nextDown(2.25), 2.25, 2.5, 64, Double.MAX_VALUE};
        int[] radii = {2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 5};
        for (int[] size : List.of(new int[]{256, 256}, new int[]{854, 480},
                new int[]{1920, 1080}, new int[]{2561, 1441}, new int[]{3840, 2160})) {
            for (int guiScale = 1; guiScale <= 6; guiScale++) {
                // Ceil-rounded GUI dimensions deliberately give unequal horizontal/vertical ratios.
                int guiW = (int) Math.ceil(size[0] / (double) guiScale);
                int guiH = (int) Math.ceil(size[1] / (double) guiScale);
                int previousRadius = 2;
                for (int i = 0; i < zooms.length; i++) {
                    var projection = new NativeMapProjection(0, 0, zooms[i], size[0], size[1], guiW, guiH);
                    int radius = projection.stagingMarkerRadiusPixels();
                    String label = "staging zoom " + zooms[i] + ", " + size[0] + "x" + size[1] + ", GUI " + guiScale;
                    check(radius == radii[i], label + " has the expected framebuffer radius");
                    check(radius >= previousRadius && radius >= 2 && radius <= 5, label + " remains monotone and bounded");
                    previousRadius = radius;
                    if (zooms[i] < .125 || zooms[i] > 64) continue;

                    // Independent projection contract for drawStaging's framebuffer-space cross:
                    // local rectangle [-r, r+1) is 2r+1 physical pixels, with a 1-pixel stroke.
                    // These are geometry checks, not an invocation of the private GPU drawing method.
                    var start = projection.screen(-radius / zooms[i], -radius / zooms[i]);
                    var end = projection.screen((radius + 1.0) / zooms[i], (radius + 1.0) / zooms[i]);
                    double physicalWidth = (end.x() - start.x()) * size[0] / guiW;
                    double physicalHeight = (end.z() - start.z()) * size[1] / guiH;
                    near(physicalWidth, 2 * radii[i] + 1, 1e-8, label + " horizontal arm does not grow with GUI scale");
                    near(physicalHeight, 2 * radii[i] + 1, 1e-8, label + " vertical arm does not grow with GUI scale");
                    var center = projection.screen(0, 0);
                    var stroke = projection.screen(1 / zooms[i], 1 / zooms[i]);
                    near((stroke.x() - center.x()) * size[0] / guiW, 1, 1e-8, label + " vertical stroke remains one physical pixel");
                    near((stroke.z() - center.z()) * size[1] / guiH, 1, 1e-8, label + " horizontal stroke remains one physical pixel");
                }
            }
        }
        for (double invalidZoom : new double[]{0, -1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            var projection = new NativeMapProjection(0, 0, invalidZoom, 1920, 1080, 480, 270);
            check(!projection.valid() && projection.stagingMarkerRadiusPixels() == 2, "invalid zoom uses the safe minimum marker radius");
        }
        check(new NativeMapProjection(0, 0, 4, 0, 1080, 480, 270).stagingMarkerRadiusPixels() == 2,
                "missing framebuffer uses the safe minimum marker radius");
        check(new NativeMapProjection(Double.NaN, 0, 4, 1920, 1080, 480, 270).stagingMarkerRadiusPixels() == 2,
                "invalid camera uses the safe minimum marker radius");
    }
    private static void creationAndUndo() {
        var m = new NativeMapEditModel(base());
        m.tool(Tool.SAFE); m.begin(p(50, 50), 1); m.update(p(70, 50)); m.finish(p(70, 50));
        near(m.draft().safeZone().radius(), 20, 1e-8, "circle drag radius");
        check(m.dirty() && m.canUndo(), "local draft dirty");
        m.undo(); check(m.draft().equals(base()), "undo entire drag once");
        m.redo(); check(m.draft().safeZone() != null, "redo circle");
        m.tool(Tool.BOMB); m.begin(p(25, 25), 1); m.finish(p(30, 25));
        check(m.draft().bombingZones().size() == 1 && m.draft().bombingZones().getFirst().id() == 0, "unsaved ID assigned by server later");
        check(m.draft().bombingZones().getFirst().missilesPerMinute() == MapZoneModel.DEFAULT_MISSILES_PER_MINUTE, "bomb default properties");
        m.tool(Tool.FLIGHT); m.begin(p(10, 10), 1); m.finish(p(200, 80));
        near(m.draft().flightPath().endX(), 100, 1e-8, "flight endpoint clamped");
        check(m.draft().blueDamagePerSecond() == base().blueDamagePerSecond(), "preserve damage config");
        check(m.draft().visualStyle().equals(base().visualStyle()), "preserve palette");
        check(MapZoneModel.validate(m.draft()).isEmpty(), "valid created draft");
        m.tool(Tool.MAP); m.begin(p(-10, -10), 1); m.finish(p(150, 160));
        check(m.draft().minX() == -10 && m.draft().maxZ() == 160, "rectangle creation");
        check(m.draft().safeZone() != null && m.draft().bombingZones().size() == 1 && m.draft().flightPath() != null,
                "redefining bounds preserves children");
    }
    private static void movementAndHandles() {
        var m = new NativeMapEditModel(base());
        m.tool(Tool.SAFE); m.begin(p(50, 50), 1); m.finish(p(60, 50));
        m.tool(Tool.SELECT); m.begin(p(50, 50), 1); m.finish(p(250, 50));
        check(m.selected().kind() == Kind.SAFE && m.draft().safeZone().centerX() == 100, "circle moving center constrained to map");
        m.begin(p(110, 50), 1); m.finish(p(140, 50));
        near(m.draft().safeZone().radius(), 40, 1e-8, "circle radius handle allowed outside map");
        m.tool(Tool.FLIGHT); m.begin(p(10, 10), 1); m.finish(p(90, 90));
        m.tool(Tool.SELECT); m.begin(p(40, 40), 1); m.finish(p(200, 200));
        near(m.draft().flightPath().endX(), 100, 1e-8, "whole flight drag clamped");
        near(m.draft().flightPath().startX(), 20, 1e-8, "whole flight preserves vector");
        m.begin(p(20, 20), 1); m.finish(p(0, 100));
        check(m.draft().flightPath().startX() == 0 && m.draft().flightPath().startZ() == 100, "flight start handle");
        var original = m.draft();
        m.begin(p(0, 30), 1); m.finish(p(20, 50));
        check(m.selected().kind() == Kind.MAP, "select map boundary");
        near(m.draft().minX(), 20, 1e-8, "map move");
        near(m.draft().safeZone().centerX(), original.safeZone().centerX() + 20, 1e-8, "map move translates safe zone");
        near(m.draft().flightPath().startZ(), original.flightPath().startZ() + 20, 1e-8, "map move translates flight");
        m.begin(p(120, 120), 1); m.finish(p(150, 170));
        check(m.draft().maxX() == 150 && m.draft().maxZ() == 170, "map corner resize");
    }
    private static void rejectedBoundsKeepObjects() {
        var m = new NativeMapEditModel(base());
        m.tool(Tool.SAFE); m.begin(p(90, 90), 1); m.finish(p(95, 90));
        var old = m.draft();
        m.tool(Tool.MAP); m.begin(p(0, 0), 1); check(!m.finish(p(20, 20)), "reject bounds excluding circle center");
        check(m.draft().equals(old), "invalid shrink keeps entire prior draft");
        check(com.example.examplemod.localization.TestLanguages.chinese(m.message()).contains("中心"), "explain invalid shrink");
        m.tool(Tool.FLIGHT); m.begin(p(0, 0), 1); check(!m.finish(p(0, 0)), "reject zero-length flight");
        check(m.draft().equals(old), "zero flight leaves previous geometry untouched");
        m.tool(Tool.MAP); m.begin(p(50, 50), 1); check(!m.finish(p(50, 50)), "reject zero-area rectangle");
        check(m.draft().equals(old), "no accidental delete on bad rectangle");
        m.tool(Tool.BOMB); check(m.begin(p(-10, 50), 1) && !m.dragging(), "outside center blocked and consumed");
    }
    private static void deleteOnlyDraft() {
        var m = new NativeMapEditModel(base());
        m.tool(Tool.BOMB); m.begin(p(40, 40), 1); m.finish(p(45, 40));
        m.tool(Tool.BOMB); m.begin(p(60, 60), 1); m.finish(p(65, 60));
        m.tool(Tool.SELECT); m.begin(p(40, 40), 1); m.finish(p(40, 40));
        check(m.selected().kind() == Kind.BOMB && m.selected().index() == 0, "select correct bomb");
        check(m.deleteSelection() && m.draft().bombingZones().size() == 1, "delete exact selected bomb");
        near(m.draft().bombingZones().getFirst().area().centerX(), 60, 1e-8, "other bomb preserved");
        m.undo(); check(m.draft().bombingZones().size() == 2, "undo deletion");
        m.begin(p(0, 20), 1); m.finish(p(0, 20));
        check(!m.deleteSelection() && m.selected().kind() == Kind.MAP, "model delegates map deletion to parent confirmation");
        check(base().bombingZones().isEmpty(), "original saved input is immutable");
    }
    private static void cancellingAndHistory() {
        var m = new NativeMapEditModel(base());
        m.tool(Tool.SAFE); m.begin(p(50, 50), 1);
        for (int i = 1; i <= 1000; i++) m.update(p(50 + i / 100.0, 50));
        check(m.cancelDrag() && m.draft().equals(base()) && !m.canUndo(), "cancel live drag without history entry");
        m.begin(p(50, 50), 1); m.finish(p(60, 50));
        var circle = m.draft();
        m.tool(Tool.SELECT); m.begin(p(50, 50), 1); m.update(p(30, 40));
        m.tool(Tool.BOMB);
        check(m.draft().equals(circle) && !m.dragging(), "tool switch cancels pending move");
        for (int i = 0; i < 80; i++) {
            m.tool(Tool.SAFE); m.begin(p(50, 50), 1); m.finish(p(60 + i / 100.0, 50));
        }
        int count = 0; while (m.canUndo()) { m.undo(); count++; }
        check(count == 64, "bounded undo history");
        check(m.canRedo(), "redo exists");
        m.tool(Tool.SAFE); m.begin(p(10, 10), 1); m.finish(p(20, 10));
        check(!m.canRedo(), "new edit clears redo branch");
    }
    private static void boundsAndLimits() {
        var m = new NativeMapEditModel(base());
        m.tool(Tool.BOMB);
        for (int i = 0; i < 32; i++) { m.begin(p(i + 1, 10), 1); m.finish(p(i + 2, 10)); }
        check(m.draft().bombingZones().size() == 32 && MapZoneModel.validate(m.draft()).isEmpty(), "32 unsaved zones with ID0 remain valid");
        check(m.begin(p(40, 40), 1) && !m.dragging() && m.draft().bombingZones().size() == 32, "32 zone bound enforced before drag");
        var p = new NativeMapProjection(0, 0, 2, 2560, 1600, 640, 400);
        near(p.tolerance(7), 14, 1e-8, "handle hit tolerance uses native raw/GUI ratio");
        m.tool(Tool.SELECT); check(!m.begin(new Point(-100, -100), 1), "empty selection delegates native pan");
        m.tool(Tool.PAN); check(!m.begin(p(50, 50), 1), "pan delegates native map");
    }
    private static Point p(double x, double z) { return new Point(x, z); }
    private static void propertyTransactions() {
        var m = new NativeMapEditModel(base());
        m.properties("海岛", 3.125);
        check(m.draft().name().equals("海岛") && m.draft().blueDamagePerSecond() == 3.125, "native property edit");
        check(m.draft().id() == base().id() && m.draft().dimension().equals(base().dimension()), "properties preserve server identity/dimension");
        m.undo(); check(m.draft().equals(base()), "property undo");
        m.redo(); check(m.draft().name().equals("海岛"), "property redo");
        var old = m.draft();
        try { m.properties("", 2); throw new AssertionError("invalid name accepted"); }
        catch (IllegalArgumentException expected) { check(m.draft().equals(old), "invalid property preserves complete draft"); }
        try { m.properties("地图", Double.NaN); throw new AssertionError("nonfinite damage accepted"); }
        catch (IllegalArgumentException expected) { check(m.draft().equals(old), "nonfinite damage not committed"); }
        m.tool(Tool.BOMB); m.begin(p(30, 30), 1); m.finish(p(40, 30));
        var geometry = m.draft().bombingZones().getFirst().area();
        m.select(Kind.BOMB, 0);
        m.bombingProperties(0, 7, .12345, 4.56789);
        var bomb = m.draft().bombingZones().getFirst();
        check(bomb.area().equals(geometry) && bomb.id() == 0, "bomb attributes preserve geometry and unsaved ID");
        check(bomb.missilesPerMinute() == 7 && bomb.missileDamage() == .12345 && bomb.blastRadius() == 4.56789, "bomb properties retain full precision");
        old = m.draft();
        try { m.bombingProperties(0, -1, 4, 5); throw new AssertionError("negative missile count accepted"); }
        catch (IllegalArgumentException expected) { check(m.draft().equals(old), "invalid bomb properties atomic"); }
        m.undo(); check(m.draft().bombingZones().getFirst().missilesPerMinute() == MapZoneModel.DEFAULT_MISSILES_PER_MINUTE, "bomb property undo");
        m.select(Kind.SAFE, 0); check(m.selected() == null, "absent inspector object does not create geometry");
        m.select(Kind.MAP, 0); check(m.selected().kind() == Kind.MAP && m.tool() == Tool.SELECT, "inspector selection enters selection tool");
    }
    private static void compactViewport() {
        for (int[] size : List.of(new int[]{320, 240}, new int[]{426, 240}, new int[]{640, 360}, new int[]{854, 480}, new int[]{960, 540})) {
            for (boolean open : new boolean[]{false, true}) {
                var layout = NativeMapEditorLayout.of(size[0], size[1], open);
                check(layout.top() >= 42 + 44 && layout.bottom() <= size[1] - 72, "independent viewport avoids its map tools/readouts");
                check(layout.mapRight() + layout.inspectorWidth() == size[0], "inspector consumes a distinct viewport region");
                check(layout.mapRight() >= 160 && layout.bottom() > layout.top(), "small window retains usable terrain");
                check(layout.inMap(1, layout.top() + 1), "terrain accepts input");
                check(!layout.inMap(layout.mapRight(), layout.top() + 1), "inspector/right edge cannot edit terrain");
                check(!layout.inMap(1, layout.bottom()), "footer cannot edit terrain");
                check(layout.top() + 14 + (layout.propertyRows() - 1) * 38 + 20 <= layout.bottom() - 20,
                        "last property input stays above pager even at 320x240");
                check(!layout.inMap(1, 34), "root tabs cannot edit terrain");
            }
        }
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean success, String message) { checks++; if (!success) throw new AssertionError(message); }
}
