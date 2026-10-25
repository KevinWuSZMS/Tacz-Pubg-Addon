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
        CombatHudTextures.draw(g, CombatHudTextures.HEAL, x, y, size, size, color);
    }
    public static void runner(GuiGraphics g, int x, int y, int size, int color) {
        CombatHudTextures.draw(g, CombatHudTextures.SPRINT, x, y, size, size, color);
    }
}
