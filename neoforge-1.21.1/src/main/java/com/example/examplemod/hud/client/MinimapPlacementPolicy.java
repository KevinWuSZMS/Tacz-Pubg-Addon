package com.example.examplemod.hud.client;

/** The minimap is the single movable anchor for its header and weapon list. */
public final class MinimapPlacementPolicy {
    private MinimapPlacementPolicy() {}
    public static boolean mapChild(String id) { return id.equals("match_phase") || id.equals("weapon_stack"); }
    public static boolean nativeMapComponent(String id) { return id != null && (id.equals("xaero_minimap") || mapChild(id)); }
    public static boolean movable(String id, boolean nativeAvailable) { return id != null && (!nativeMapComponent(id) || nativeAvailable); }
    public static HudLayoutModel.Rect bottomRight(int width, int height, int mapWidth, int mapHeight, int margin) {
        return new HudLayoutModel.Rect(Math.max(0, width - mapWidth - margin), Math.max(0, height - mapHeight - margin), mapWidth, mapHeight);
    }
    public static HudLayoutModel.Rect header(HudLayoutModel.Rect map, int height, int gap) {
        return new HudLayoutModel.Rect(map.x(), map.y() - height - gap, map.width(), height);
    }
    public static HudLayoutModel.Rect weaponsLeft(HudLayoutModel.Rect map, int desiredWidth, int height, int gap) {
        // No right-side fallback: use the available space to the left on small viewports.
        int width = Math.max(1, Math.min(desiredWidth, map.x() - gap));
        return new HudLayoutModel.Rect(map.x() - gap - width, map.y() + map.height() - height, width, height);
    }
    public static HudLayoutModel.Rect clampGroup(HudLayoutModel.Rect map, int screenWidth, int screenHeight,
            int headerHeight, int headerGap, int weaponMinimumWidth, int weaponGap) {
        int maxX = Math.max(0, screenWidth - map.width()), maxY = Math.max(0, screenHeight - map.height());
        int minX = Math.min(maxX, weaponMinimumWidth + weaponGap);
        int minY = Math.min(maxY, headerHeight + headerGap);
        return new HudLayoutModel.Rect(Math.clamp(map.x(), minX, maxX), Math.clamp(map.y(), minY, maxY), map.width(), map.height());
    }
}
