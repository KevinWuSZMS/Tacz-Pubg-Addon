package com.example.examplemod.vitals.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/** Stable GUI primitives; clockwise arcs start at twelve o'clock. */
public final class CombatHudDraw {
    private CombatHudDraw() {}
    public static void disk(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            int x = (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - y * y)));
            g.fill(cx - x, cy + y, cx + x + 1, cy + y + 1, color);
        }
    }
    public static void arc(GuiGraphics g, double cx, double cy, double radius, double line, double start, double sweep, int color) {
        if (!(sweep > 0) || radius <= 0) return;
        int segments = Math.max(4, (int) Math.ceil(Math.min(360, sweep) * radius / 100));
        var buffer = g.bufferSource().getBuffer(RenderType.gui());
        var matrix = g.pose().last().pose();
        double inner = Math.max(0, radius - line);
        for (int i = 0; i < segments; i++) {
            double a = Math.toRadians(start + sweep * i / segments - 90), b = Math.toRadians(start + sweep * (i + 1) / segments - 90);
            buffer.addVertex(matrix, (float) (cx + Math.cos(a) * radius), (float) (cy + Math.sin(a) * radius), 0).setColor(color);
            buffer.addVertex(matrix, (float) (cx + Math.cos(a) * inner), (float) (cy + Math.sin(a) * inner), 0).setColor(color);
            buffer.addVertex(matrix, (float) (cx + Math.cos(b) * inner), (float) (cy + Math.sin(b) * inner), 0).setColor(color);
            buffer.addVertex(matrix, (float) (cx + Math.cos(b) * radius), (float) (cy + Math.sin(b) * radius), 0).setColor(color);
        }
    }
    public static void plus(GuiGraphics g, int x, int y, int size, int color) {
        int stroke = Math.max(2, size / 4), middle = (size - stroke) / 2;
        g.fill(x + middle, y, x + middle + stroke, y + size, color);
        g.fill(x, y + middle, x + size, y + middle + stroke, color);
    }
    public static void runner(GuiGraphics g, int x, int y, int size, int color) {
        float scale = size / 16f;
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(scale, scale, 1);
        disk(g, 10, 2, 2, color);
        line(g, 8, 5, 6, 9, 2, color); line(g, 8, 5, 12, 7, 2, color); line(g, 12, 7, 14, 5, 2, color);
        line(g, 8, 5, 4, 6, 2, color); line(g, 4, 6, 2, 10, 2, color);
        line(g, 6, 9, 10, 12, 2, color); line(g, 10, 12, 8, 16, 2, color);
        line(g, 6, 9, 4, 12, 2, color); line(g, 4, 12, 0, 13, 2, color);
        g.pose().popPose();
    }
    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int thickness, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) { int x = x1 + (x2 - x1) * i / Math.max(1, steps), y = y1 + (y2 - y1) * i / Math.max(1, steps);
            g.fill(x, y, x + thickness, y + thickness, color); }
    }
}
