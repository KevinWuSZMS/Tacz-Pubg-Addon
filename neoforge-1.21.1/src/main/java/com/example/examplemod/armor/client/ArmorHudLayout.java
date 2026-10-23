package com.example.examplemod.armor.client;

import com.example.examplemod.vitals.client.PubgVitalsLayout;

/** Bounds and proportional fitting for the compact equipment / weapon / status row. */
public final class ArmorHudLayout {
    public static final int HEIGHT = 24;
    public static final int TEXT_HEIGHT = 9;
    public static final float LOADED_SCALE = 1.55f;
    public record Cell(int x, int y, int width, int height) {}
    public record Metrics(int cellWidth, int logicalWidth, int logicalHeight, float scale, int iconSize, int percentageTop) {
        public int weaponLeft() { return cellWidth * 3 + 2; }
        public int loadedLeft() { return weaponLeft() + 13; }
        public int reserveLeft(int loadedTextWidth) { return weaponLeft() + 15 + loadedWidth(loadedTextWidth); }
        public int runnerLeft() { return logicalWidth - 29; }
        public int recoveryLeft() { return logicalWidth - 12; }
        public int cellLeft(int index) { return 1 + index * cellWidth; }
        public int cellContentWidth() { return cellWidth - 2; }
    }
    public static int loadedWidth(int textWidth) { return (int) Math.ceil(textWidth * (double) LOADED_SCALE); }
    public static int weaponWidth(int loadedTextWidth, int reserveTextWidth) {
        return Math.max(96, 13 + loadedWidth(loadedTextWidth) + reserveTextWidth + 37);
    }
    public static Metrics metrics(int width, int height, int icon, int gap, int percentageWidth, int weaponWidth) {
        // Two compact baselines retain full-size percentages without shrinking the complete 182px row.
        int iconSize = Math.min(icon, Math.max(1, height - TEXT_HEIGHT - 1));
        int cell = Math.max(iconSize, percentageWidth) + Math.max(2, gap);
        int logicalWidth = cell * 3 + weaponWidth + 2, logicalHeight = Math.max(iconSize + TEXT_HEIGHT + 1, 20);
        return new Metrics(cell, logicalWidth, logicalHeight,
                Math.min(1, Math.min(width / (float) logicalWidth, height / (float) logicalHeight)), iconSize, iconSize + 1);
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
