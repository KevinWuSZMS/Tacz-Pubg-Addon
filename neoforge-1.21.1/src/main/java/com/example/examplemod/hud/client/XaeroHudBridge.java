package com.example.examplemod.hud.client;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Xaero 26.6.0's own transform transaction; no reflection or replacement settings file. */
final class XaeroHudBridge {
    private XaeroHudBridge() {}
    static boolean available() {
        var container = ModList.get().getModContainerById("xaerominimap");
        if (container.isEmpty() || !container.get().getModInfo().getVersion().toString().equals("26.6.0")) return false;
        try { return Native.ready(); }
        catch (LinkageError | RuntimeException unavailable) { return false; }
    }
    static HudLayoutModel.Rect bounds(int width, int height) {
        if (available()) return Native.bounds(width, height);
        var fallback = com.example.examplemod.zones.client.xaero.XaeroHudAnchor.defaultMinimap(width, height);
        return new HudLayoutModel.Rect(fallback.x(), fallback.y(), fallback.width(), fallback.height());
    }
    static void prepareReset() { if (available()) Native.initializePending(); }
    static void beginEdit() { if (available()) Native.begin(); }
    static void preview(HudLayoutModel.Position position, int width, int height) {
        if (available()) Native.preview(position, width, height);
    }
    static void previewDefault() { if (available()) Native.previewDefault(); }
    static void saveEdit() throws IOException { if (available()) Native.save(); }
    static void cancelEdit() { if (available()) Native.cancel(); }
    static void tick() { if (available()) Native.tick(); }

    // Isolated linkage keeps the mod's editor functional with Xaero absent.
    private static final class Native {
        private static xaero.hud.module.ModuleTransform original, lastApplied;
        private static int lastWidth = -1, lastHeight = -1;
        private static double lastScale = -1;
        private static boolean changed;
        private static long nextResetRetry;
        private static xaero.hud.module.HudModule<xaero.hud.minimap.module.MinimapSession> module() {
            return xaero.hud.minimap.BuiltInHudModules.MINIMAP;
        }
        private static boolean ready() {
            var mod = xaero.common.HudMod.INSTANCE;
            return mod != null && mod.isLoadedClient() && mod.getHud() != null
                    && module().getCurrentSession() != null && module().getCurrentSession().isActive()
                    && module().getCurrentSession().getProcessor() != null;
        }
        private static HudLayoutModel.Rect bounds(int width, int height) {
            var frame = com.example.examplemod.zones.client.xaero.XaeroHudAnchor.placement(width, height, true);
            return frame == null ? defaultRect(width, height, 132, 132)
                    : new HudLayoutModel.Rect(frame.x(), frame.y(), frame.width(), frame.height());
        }
        private static void begin() {
            original = module().getConfirmedTransform().copy();
            changed = false;
        }
        private static void preview(HudLayoutModel.Position position, int width, int height) {
            if (original == null) begin();
            var current = bounds(width, height);
            var placed = HudLayoutModel.resolve(position, width, height, current);
            place(module().getUnconfirmedTransform(), placed, width, height);
            changed = true;
        }
        private static void previewDefault() {
            if (original == null) begin();
            var mc = Minecraft.getInstance();
            int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
            var size = bounds(width, height);
            var target = new xaero.hud.module.ModuleTransform();
            place(target, defaultRect(width, height, size.width(), size.height()), width, height);
            copy(module().getUnconfirmedTransform(), target);
            changed = true;
        }
        private static HudLayoutModel.Rect defaultRect(int width, int height, int mapWidth, int mapHeight) {
            int margin = com.example.examplemod.pubgsettings.PubgSettingsService.client().integer("map.defaultMargin");
            return MinimapPlacementPolicy.bottomRight(width, height, mapWidth, mapHeight, margin);
        }
        private static void place(xaero.hud.module.ModuleTransform transform, HudLayoutModel.Rect rect, int width, int height) {
            rect = com.example.examplemod.zones.client.xaero.XaeroHudAnchor.moduleForVisible(rect);
            int right = Math.max(0, width - rect.width() - rect.x());
            int bottom = Math.max(0, height - rect.height() - rect.y());
            transform.centered = false;
            transform.fromRight = rect.x() > right;
            transform.fromBottom = rect.y() > bottom;
            transform.x = transform.fromRight ? right : rect.x();
            transform.y = transform.fromBottom ? bottom : rect.y();
            transform.fromOldSystem = false;
        }
        private static void save() throws IOException {
            if (original == null) return;
            if (!changed) { original = null; module().cancelTransform(); return; }
            var pending = module().getUnconfirmedTransform().copy();
            try {
                module().confirmTransform();
                xaero.common.HudMod.INSTANCE.getSettings().saveSettings();
            } catch (IOException | RuntimeException failure) {
                IOException error = failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure);
                try {
                    module().setTransform(original.copy());
                    xaero.common.HudMod.INSTANCE.getSettings().saveSettings();
                } catch (IOException | RuntimeException rollback) { error.addSuppressed(rollback); }
                try { copy(module().getUnconfirmedTransform(), pending); }
                catch (RuntimeException rollback) { error.addSuppressed(rollback); }
                throw error;
            }
            lastApplied = module().getConfirmedTransform().copy();
            rememberDimensions();
            original = null;
            changed = false;
        }
        private static void cancel() {
            if (original != null) module().setTransform(original.copy());
            original = null;
            changed = false;
            lastApplied = module().getConfirmedTransform().copy();
            lastWidth = lastHeight = -1; // Cancel after a resize must re-clamp the saved normalized placement.
        }
        private static void tick() {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (!HudLayoutClient.editing()) initializePending();
            if (HudLayoutClient.nativeMapResetPending() || HudLayoutClient.editing() || mc.screen != null) return;
            var position = HudLayoutClient.xaeroPosition();
            var current = module().getConfirmedTransform();
            if (position == null) { lastApplied = null; return; }
            boolean resized = lastWidth != mc.getWindow().getGuiScaledWidth()
                    || lastHeight != mc.getWindow().getGuiScaledHeight() || lastScale != mc.getWindow().getGuiScale();
            if (lastApplied != null && !same(current, lastApplied)) {
                // A later native Xaero edit is deliberate. Adopt it rather than fighting that editor.
                var rect = bounds(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                HudLayoutClient.adoptXaeroPosition(HudLayoutModel.normalize(rect.x(), rect.y(),
                        mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), rect.width(), rect.height()));
                lastApplied = current.copy();
                rememberDimensions();
                return;
            }
            if (lastApplied == null || resized) {
                int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
                var rect = HudLayoutModel.resolve(position, width, height, bounds(width, height));
                var placed = current.copy();
                place(placed, rect, width, height);
                module().setTransform(placed);
                lastApplied = placed.copy();
                rememberDimensions();
            }
        }
        private static void initializePending() {
            if (!HudLayoutClient.nativeMapResetPending() || module().getCurrentSession() == null
                    || !module().getCurrentSession().isActive() || Minecraft.getInstance().level == null
                    || System.nanoTime() < nextResetRetry) return;
            var mc = Minecraft.getInstance();
            int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
            xaero.hud.module.ModuleTransform previous = null;
            try {
                var frame = com.example.examplemod.zones.client.xaero.XaeroHudAnchor.placement(width, height, true);
                if (frame == null) return; // Never persist a guessed fallback size during native initialization.
                var rect = defaultRect(width, height, frame.width(), frame.height());
                previous = module().getConfirmedTransform().copy();
                var placed = new xaero.hud.module.ModuleTransform();
                place(placed, rect, width, height);
                module().setTransform(placed);
                xaero.common.HudMod.INSTANCE.getSettings().saveSettings();
                var actual = bounds(width, height);
                HudLayoutClient.completeNativeMapReset(HudLayoutModel.normalize(actual.x(), actual.y(),
                        width, height, actual.width(), actual.height()));
                lastApplied = module().getConfirmedTransform().copy();
                rememberDimensions();
            } catch (IOException | RuntimeException failure) {
                if (previous != null) module().setTransform(previous);
                nextResetRetry = System.nanoTime() + 5_000_000_000L;
                LogUtils.getLogger().warn("Could not persist the one-time minimap layout reset; will retry", failure);
            }
        }
        private static void rememberDimensions() {
            var window = Minecraft.getInstance().getWindow();
            lastWidth = window.getGuiScaledWidth();
            lastHeight = window.getGuiScaledHeight();
            lastScale = window.getGuiScale();
        }
        private static boolean same(xaero.hud.module.ModuleTransform a, xaero.hud.module.ModuleTransform b) {
            return a.x == b.x && a.y == b.y && a.centered == b.centered && a.fromRight == b.fromRight
                    && a.fromBottom == b.fromBottom && a.flippedHor == b.flippedHor
                    && a.flippedVer == b.flippedVer && a.fromOldSystem == b.fromOldSystem;
        }
        private static void copy(xaero.hud.module.ModuleTransform target, xaero.hud.module.ModuleTransform source) {
            target.x = source.x; target.y = source.y; target.centered = source.centered;
            target.fromRight = source.fromRight; target.fromBottom = source.fromBottom;
            target.flippedHor = source.flippedHor; target.flippedVer = source.flippedVer;
            target.fromOldSystem = source.fromOldSystem;
        }
    }
}
