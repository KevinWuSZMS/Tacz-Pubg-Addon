package com.example.examplemod.zones.client.xaero;

import net.minecraft.client.Minecraft;
import xaero.common.HudMod;
import xaero.hud.minimap.module.MinimapSession;

/** Public Xaero HUD placement, including user positioning and Xaero's screen-edge pushboxes. */
public final class XaeroHudAnchor {
    private XaeroHudAnchor() {}
    public record Bounds(int x, int y, int width, int height) {
        public boolean overlaps(int left, int top, int right, int bottom) {
            return left < x + width && right > x && top < y + height && bottom > y;
        }
    }
    public static Bounds defaultMinimap(int width, int height) {
        int margin = com.example.examplemod.pubgsettings.PubgSettingsService.client().integer("map.defaultMargin");
        int size = Math.max(32, Math.min(132, Math.min(width, height) - margin * 2));
        return new Bounds(Math.max(0, width - size - margin), Math.max(0, height - size - margin), size, size);
    }
    public static Bounds minimap() {
        var window = Minecraft.getInstance().getWindow();
        return placement(window.getGuiScaledWidth(), window.getGuiScaledHeight(), false);
    }
    /** Fresh geometry also follows the native unconfirmed transform in our HUD editor. */
    public static Bounds placement(int width, int height, boolean preview) {
        try {
            var mod = HudMod.INSTANCE;
            var mc = Minecraft.getInstance();
            if (mod == null || !mod.isLoadedClient() || mod.getHud() == null || mc.level == null) return null;
            for (var module : mod.getHud().getModuleManager().getModules()) {
                if (!(module.getCurrentSession() instanceof MinimapSession session) || !session.isActive()) continue;
                if (!preview && (mc.screen != null && session.getHideMinimapUnderScreen()
                        || mc.getDebugOverlay().showDebugScreen() && session.getHideMinimapUnderF3())) return null;
                double scale = mc.getWindow().getGuiScale();
                var placed = new xaero.hud.pushbox.PushboxHandler.State().resetForModule(session, width, height, scale);
                var handler = mod.getHudRenderer().getPushboxHandler();
                handler.applyScreenEdges(placed, width, height, scale);
                handler.applyPushboxes(mod.getHud().getPushboxManager(), placed, width, height, scale);
                var config = mod.getHudConfigs().getClientConfigManager();
                if (session.getProcessor().isEnlargedMap() && config.getEffective(
                        xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions.CENTERED_ENLARGED)) {
                    placed.x = (width - session.getWidth(scale)) / 2;
                    placed.y = (height - session.getWidth(scale)) / 2;
                }
                var frame = com.example.examplemod.hud.client.XaeroMapFrameGeometry.visible(placed.x, placed.y,
                        session.getProcessor().getMinimapSize() / 2,
                        xaero.hud.gui.util.GuiUtils.getMinimapScale(config) / scale,
                        com.example.examplemod.hud.client.XaeroMapFrameGeometry.frameBorder(config.getEffective(
                                xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions.FRAME)));
                return new Bounds(frame.x(), frame.y(), frame.width(), frame.height());
            }
        } catch (LinkageError | RuntimeException unavailable) {
            // Xaero is optional. A compact independent timer remains available if its API changes.
        }
        return null;
    }
    /** Inverse of the exact visible-frame transform; persistence still uses Xaero's own module. */
    public static com.example.examplemod.hud.client.HudLayoutModel.Rect moduleForVisible(
            com.example.examplemod.hud.client.HudLayoutModel.Rect visible) {
        var mc = Minecraft.getInstance();
        var session = xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) return visible;
        var config = HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
        double scale = mc.getWindow().getGuiScale();
        double ratio = xaero.hud.gui.util.GuiUtils.getMinimapScale(config) / scale;
        int border = com.example.examplemod.hud.client.XaeroMapFrameGeometry.frameBorder(config.getEffective(
                xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions.FRAME));
        return new com.example.examplemod.hud.client.HudLayoutModel.Rect(
                com.example.examplemod.hud.client.XaeroMapFrameGeometry.moduleCoordinate(visible.x(), session.getProcessor().getMinimapSize() / 2, ratio, border),
                com.example.examplemod.hud.client.XaeroMapFrameGeometry.moduleCoordinate(visible.y(), session.getProcessor().getMinimapSize() / 2, ratio, border),
                session.getWidth(scale), session.getHeight(scale));
    }
}
