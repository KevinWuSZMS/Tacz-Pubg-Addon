package com.example.examplemod.hud.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

/** Shared placement for all addon HUD renderers. Optional positions do not change default layouts. */
public final class HudLayoutClient {
    public static final List<String> IDS = List.of("vitals", "equipment", "medicine_progress", "medicine_complete", "throwable_status",
            "throwable_wheel", "medicine_wheel", "match_phase", "alive_counter", "compass", "match_alert",
            "match_result", "squad", "map_preview", "spectator", "flight", "loot_pickup", "area_selection", "xaero_minimap", "weapon_stack",
            "combat_messages", "combat_feed");
    @FunctionalInterface public interface Defaults { HudLayoutModel.Rect get(int screenWidth, int screenHeight); }
    @FunctionalInterface public interface Preview {
        void draw(GuiGraphics graphics, HudLayoutModel.Rect rectangle, boolean selected);
    }
    public record Definition(String id, Component label, Defaults defaults, Preview preview) {}
    private record Observed(int screenWidth, int screenHeight, HudLayoutModel.Rect rectangle) {}
    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<String, Observed> OBSERVED = new LinkedHashMap<>();
    private static final java.util.Set<String> EXPLICIT_PREVIEWS = new java.util.HashSet<>();
    private static Map<String, HudLayoutModel.Position> saved = Map.of();
    private static HudLayoutDraft draft;
    private static boolean loaded;
    private static boolean nativeMapResetPending = true;
    private static String loadError = "";

    static {
        // Eager definitions are essential: hidden HUDs must be editable before their first render.
        for (String id : IDS) DEFINITIONS.put(id, new Definition(id,
                Component.translatable("screen.tacz_pubg.hud_layout.component." + id),
                (w, h) -> HudLayoutPreviews.defaults(id, w, h),
                (g, r, selected) -> HudLayoutPreviews.draw(g, id, r, selected)));
    }
    private HudLayoutClient() {}

    public static Scope apply(GuiGraphics graphics, String id, int x, int y, int width, int height) {
        var rectangle = resolveRect(graphics, id, x, y, width, height);
        graphics.pose().pushPose();
        graphics.pose().translate(rectangle.x() - x, rectangle.y() - y, 0);
        return new Scope(graphics);
    }
    public static final class Scope implements AutoCloseable {
        private GuiGraphics graphics;
        private Scope(GuiGraphics graphics) { this.graphics = graphics; }
        @Override public void close() {
            if (graphics != null) { graphics.pose().popPose(); graphics = null; }
        }
    }
    public static HudLayoutModel.Rect resolveRect(GuiGraphics graphics, String id, int x, int y, int width, int height) {
        return resolve(id, graphics.guiWidth(), graphics.guiHeight(), x, y, width, height);
    }
    public static HudLayoutModel.Rect resolve(String id, int screenWidth, int screenHeight,
            int x, int y, int width, int height) {
        ensureLoaded();
        var fallback = new HudLayoutModel.Rect(x, y, width, height);
        if (DEFINITIONS.containsKey(id) && !(Minecraft.getInstance().screen instanceof HudLayoutEditorScreen))
            OBSERVED.put(id, new Observed(screenWidth, screenHeight, fallback));
        return MinimapPlacementPolicy.mapChild(id) ? fallback
                : HudLayoutModel.resolve(positions().get(id), screenWidth, screenHeight, fallback);
    }
    public static boolean hasPosition(String id) { ensureLoaded(); return !MinimapPlacementPolicy.mapChild(id) && positions().containsKey(id); }

    /** A renderer can replace the built-in sample with its own representative inactive-state preview. */
    public static void registerPreview(String id, Defaults defaults, Preview preview) {
        Definition previous = DEFINITIONS.get(id);
        if (previous == null) throw new IllegalArgumentException("Unknown HUD component " + id);
        DEFINITIONS.put(id, new Definition(id, previous.label(), defaults, preview));
        EXPLICIT_PREVIEWS.add(id);
    }
    public static List<Definition> definitions() { return List.copyOf(DEFINITIONS.values()); }
    public static Definition definition(String id) { return DEFINITIONS.get(id); }
    public static boolean editing() { return draft != null; }
    static String loadError() { ensureLoaded(); return loadError; }

    static HudLayoutModel.Rect previewRect(String id, int width, int height) {
        ensureLoaded();
        if (id.equals("match_phase")) return mapHeader(width, height);
        if (id.equals("weapon_stack")) return com.example.examplemod.vitals.client.WeaponStackRenderer.bounds(width, height);
        if (id.equals("xaero_minimap") && XaeroHudBridge.available()) return XaeroHudBridge.bounds(width, height);
        var definition = DEFINITIONS.get(id);
        var observed = OBSERVED.get(id);
        var fallback = !EXPLICIT_PREVIEWS.contains(id) && observed != null && observed.screenWidth == width && observed.screenHeight == height
                ? observed.rectangle : definition.defaults.get(width, height);
        return HudLayoutModel.resolve(positions().get(id), width, height, fallback);
    }
    static void beginEdit() {
        ensureLoaded();
        if (draft != null) cancelEdit();
        XaeroHudBridge.prepareReset();
        draft = new HudLayoutDraft(saved);
        OBSERVED.clear();
        XaeroHudBridge.beginEdit();
    }
    public static HudLayoutModel.Rect mapHeader(int width, int height) {
        var map = mapBounds(width, height);
        var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
        return MinimapPlacementPolicy.header(map, settings.integer("map.headerHeight"), settings.integer("map.headerGap"));
    }
    public static HudLayoutModel.Rect mapBounds(int width, int height) {
        ensureLoaded();
        if (XaeroHudBridge.available()) return XaeroHudBridge.bounds(width, height);
        var fallback = XaeroHudBridge.bounds(width, height);
        return HudLayoutModel.resolve(positions().get("xaero_minimap"), width, height, fallback);
    }
    static void move(String id, double x, double y, int width, int height) {
        if (draft == null) return;
        var rect = previewRect(id, width, height);
        if (MinimapPlacementPolicy.mapChild(id)) {
            var map = mapBounds(width, height);
            x = map.x() + x - rect.x(); y = map.y() + y - rect.y();
            id = "xaero_minimap"; rect = map;
        }
        if (id.equals("xaero_minimap")) {
            var settings = com.example.examplemod.pubgsettings.PubgSettingsService.client();
            var group = MinimapPlacementPolicy.clampGroup(new HudLayoutModel.Rect((int) Math.round(x), (int) Math.round(y),
                    rect.width(), rect.height()), width, height, settings.integer("map.headerHeight"),
                    settings.integer("map.headerGap"), 64, 4);
            x = group.x(); y = group.y();
        }
        draft.put(id, HudLayoutModel.normalize(x, y, width, height, rect.width(), rect.height()));
        if (id.equals("xaero_minimap")) XaeroHudBridge.preview(draft.get(id), width, height);
    }
    static void reset(String id) {
        if (draft == null) return;
        if (MinimapPlacementPolicy.mapChild(id)) id = "xaero_minimap";
        draft.remove(id);
        OBSERVED.remove(id);
        if (id.equals("xaero_minimap")) XaeroHudBridge.previewDefault();
    }
    static void resetAll() {
        if (draft == null) return;
        draft.clear();
        OBSERVED.clear();
        XaeroHudBridge.previewDefault();
    }
    static void resized(int width, int height) {
        if (draft != null && draft.containsKey("xaero_minimap"))
            XaeroHudBridge.preview(draft.get("xaero_minimap"), width, height);
    }
    static void saveEdit() throws IOException {
        if (draft == null) return;
        var file = FMLPaths.CONFIGDIR.get().resolve("tacz_pubg-hud.json");
        // Stage our JSON first. If native Xaero persistence fails, restore the previous JSON and
        // retain the editable draft so the user can retry or cancel.
        var next = draft.save(positions -> {
            writeLayout(file, positions);
            try { XaeroHudBridge.saveEdit(); }
            catch (IOException failure) {
                try { writeLayout(file, saved); } catch (IOException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        });
        saved = next;
        draft = null;
        loadError = "";
    }
    static void cancelEdit() {
        if (draft == null) return;
        XaeroHudBridge.cancelEdit();
        saved = draft.original();
        draft = null;
    }
    static HudLayoutModel.Position xaeroPosition() { ensureLoaded(); return saved.get("xaero_minimap"); }
    static boolean nativeMapResetPending() { ensureLoaded(); return nativeMapResetPending; }
    static void completeNativeMapReset(HudLayoutModel.Position position) throws IOException {
        var next = new LinkedHashMap<>(saved);
        next.put("xaero_minimap", position);
        HudLayoutStore.write(FMLPaths.CONFIGDIR.get().resolve("tacz_pubg-hud.json"), new HudLayoutStore.Layout(next, false));
        saved = Map.copyOf(next);
        if (draft != null) draft.put("xaero_minimap", position);
        nativeMapResetPending = false;
        OBSERVED.clear();
    }
    static void adoptXaeroPosition(HudLayoutModel.Position position) {
        if (draft != null) return;
        var next = new LinkedHashMap<>(saved);
        next.put("xaero_minimap", position);
        saved = Map.copyOf(next);
        try { writeLayout(FMLPaths.CONFIGDIR.get().resolve("tacz_pubg-hud.json"), saved); }
        catch (IOException failure) { LogUtils.getLogger().warn("Could not save changed native minimap placement", failure); }
    }
    private static Map<String, HudLayoutModel.Position> positions() { return draft == null ? saved : draft.positions(); }
    private static void writeLayout(java.nio.file.Path path, Map<String, HudLayoutModel.Position> positions) throws IOException {
        HudLayoutStore.write(path, new HudLayoutStore.Layout(positions, nativeMapResetPending));
    }
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            var layout = HudLayoutStore.loadForClient(FMLPaths.CONFIGDIR.get().resolve("tacz_pubg-hud.json"));
            saved = layout.positions(); nativeMapResetPending = layout.nativeMapResetPending();
        }
        catch (IOException failure) {
            loadError = failure.getMessage();
            LogUtils.getLogger().warn("Could not read personal PUBG HUD layout", failure);
        }
    }
}
