package com.example.examplemod.armor.client;

import com.example.examplemod.vitals.client.PubgVitalsLayout;

/** Bounds and proportional fitting for the compact equipment / weapon / status row. */
public final class ArmorHudLayout {
    public static final int HEIGHT = 24;
    public static final int TEXT_HEIGHT = 9;
    public static final float LOADED_SCALE = 1.55f;
    public record Cell(int x, int y, int width, int height) {}
    public static final int MODE_SIZE = 10, STATUS_SIZE = 15, HEAL_SIZE = 12;
    public record Metrics(float cellWidth, float logicalWidth, int logicalHeight, float scale, int iconSize,
            int percentageTop, int gap, float ammoSpacing, float percentageScale) {
        public float loadedCenter() { return logicalWidth / 2; }
        public float modeCenter() { return loadedCenter() - ammoSpacing; }
        public float reserveCenter() { return loadedCenter() + ammoSpacing; }
        public float cellCenter(int index) { return (index + .5f) * cellWidth; }
        public float statusCenter(int index) { return logicalWidth - cellCenter(2 - index); }
        public float cellLeft(int index) { return index * cellWidth + gap / 2f; }
        public float cellContentWidth() { return cellWidth - gap; }
        public float groupWidth() { return cellWidth * 3; }
    }
    public static int loadedWidth(int textWidth) { return (int) Math.ceil(textWidth * (double) LOADED_SCALE); }
    public static Metrics metrics(int width, int height, int icon, int gap, int percentageWidth,
            int loadedTextWidth, int reserveTextWidth) {
        int iconSize = Math.min(icon, Math.max(1, height - TEXT_HEIGHT - 1));
        int cellGap = Math.max(2, gap);
        float sideMinimum = 3 * (Math.max(Math.max(iconSize, STATUS_SIZE), percentageWidth * .7f) + cellGap);
        float flankWidth = Math.max(MODE_SIZE, reserveTextWidth);
        float ammoMinimumSpacing = (loadedWidth(loadedTextWidth) + flankWidth) / 2 + 3;
        float middleMinimum = 2 * (ammoMinimumSpacing + flankWidth / 2 + 4);
        // A balanced middle may grow to a third even when its ammunition needs less room.
        // Reserve three side minima as well, otherwise that growth steals icon/gap space.
        float logicalWidth = Math.max(width, Math.max(sideMinimum * 3, sideMinimum * 2 + middleMinimum));
        // Normally each group occupies one third. Very long counts may widen only the middle,
        // with equal side groups and a final uniform fit preserving the center on small screens.
        float middleWidth = Math.max(logicalWidth / 3, middleMinimum);
        float cell = (logicalWidth - middleWidth) / 6;
        float spacing = Math.min(Math.max(ammoMinimumSpacing, middleWidth / 3), middleWidth / 2 - flankWidth / 2 - 4);
        int logicalHeight = Math.max(iconSize + TEXT_HEIGHT + 1, 20);
        return new Metrics(cell, logicalWidth, logicalHeight,
                Math.min(1, Math.min(width / logicalWidth, height / (float) logicalHeight)), iconSize, iconSize + 1,
                cellGap, spacing, Math.min(1, (cell - cellGap) / Math.max(1, percentageWidth)));
    }
    public record Panel(int x, int y, int width, int height) {
        public Cell cell(int index) {
            if (index < 0 || index >= 3) throw new IllegalArgumentException("Equipment cell index must be 0, 1 or 2");
            int left = width * index / 3;
            return new Cell(x + left, y, width * (index + 1) / 3 - left, height);
        }
        public Panel move(int dx, int dy) { return new Panel(x + dx, y + dy, width, height); }
        public Panel inside(int screenWidth, int screenHeight) {
            return new Panel(Math.clamp(x, 0, Math.max(0, screenWidth - width)),
                    Math.clamp(y, 0, Math.max(0, screenHeight - height)), width, height);
        }
    }
    private ArmorHudLayout() {}
    public static Panel panel(int screenWidth, int screenHeight, int centralBarWidth) {
        int width = Math.max(1, Math.min(centralBarWidth, screenWidth));
        return new Panel(PubgVitalsLayout.left(screenWidth, width), Math.max(0, screenHeight - 42 - HEIGHT), width, HEIGHT);
    }
}
