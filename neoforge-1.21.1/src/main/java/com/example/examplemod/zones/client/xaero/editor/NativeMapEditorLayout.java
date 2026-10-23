package com.example.examplemod.zones.client.xaero.editor;

/** GUI-coordinate layout, kept independent of Xaero/rendering for small-window checks. */
public record NativeMapEditorLayout(int mapRight, int top, int bottom, int inspectorWidth, boolean compact) {
    public static NativeMapEditorLayout of(int width, int height, boolean inspectorOpen) {
        boolean compact = width < 580 || height < 340;
        int sidebar = inspectorOpen ? Math.min(compact ? 146 : 190, Math.max(100, width / 3)) : 0;
        return new NativeMapEditorLayout(Math.max(80, width - sidebar), 89,
                Math.max(90, height - 72), sidebar, compact);
    }
    public boolean inMap(double x, double y) { return x >= 0 && x < mapRight && y >= top && y < bottom; }
    public int propertyRows() { return Math.max(1, (bottom - top - 22) / 38); }
}
