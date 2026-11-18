package com.example.examplemod.vitals.client;

import com.example.examplemod.hud.client.HudLayoutModel.Rect;
import com.example.examplemod.hud.client.MinimapPlacementPolicy;

/** Five equal map-height bands; occupied hotbar slots pack upward from the bottom. */
public final class WeaponStackLayout {
    private WeaponStackLayout() {}
    public record Row(Rect area, Rect gun, Rect smallItem, Rect key, Rect count) {}

    public static Rect bounds(Rect map, int width) {
        return MinimapPlacementPolicy.weaponsLeft(map, width, map.height(), 4);
    }

    public static Row row(Rect bounds, int line, int iconHeight, int padding) {
        if (line < 0 || line >= 5) throw new IllegalArgumentException("Weapon row must be between zero and four");
        int bottom = bounds.y() + bounds.height() - bounds.height() * line / 5;
        int top = bounds.y() + bounds.height() - bounds.height() * (line + 1) / 5;
        int height = bottom - top;
        int keySize = Math.max(1, Math.min(11, Math.min(bounds.width(), (height - 3) / 2)));
        int keyX = bounds.x() + bounds.width() - keySize;
        int contentWidth = Math.max(1, keyX - 2 - bounds.x());
        int contentHeight = Math.max(1, Math.min(iconHeight, height - Math.max(2, padding)));
        int smallSize = Math.max(1, Math.min(contentWidth, contentHeight));
        int countHeight = Math.max(1, Math.min(9, (height - 3) / 2));
        int countWidth = keySize;
        return new Row(new Rect(bounds.x(), top, bounds.width(), height),
                new Rect(bounds.x(), top + (height - contentHeight) / 2, contentWidth, contentHeight),
                new Rect(Math.max(bounds.x(), keyX - 2 - smallSize), top + (height - smallSize) / 2, smallSize, smallSize),
                new Rect(keyX, top + 1, keySize, keySize),
                new Rect(bounds.x() + bounds.width() - countWidth, bottom - 1 - countHeight, countWidth, countHeight));
    }
}
