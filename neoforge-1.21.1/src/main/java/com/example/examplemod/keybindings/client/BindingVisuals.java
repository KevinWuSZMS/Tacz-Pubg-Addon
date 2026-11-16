package com.example.examplemod.keybindings.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Screen-owned, antialiased UI textures. Dimensions are in the screen's logical coordinate system. */
public final class BindingVisuals implements AutoCloseable {
    private static final int RASTER_SCALE = 2;
    private static final int MAX_TEXTURES = 384;
    private static final int MAX_PIXELS = 12_000_000;
    private static final FontRenderContext METRICS = new FontRenderContext(null, true, true);
    private static final String FONT_NAME = chooseFont();
    private final Map<Object, Texture> textures = new LinkedHashMap<>(128, .75f, true);
    private final Map<TextKey, Float> measuredWidths = new LinkedHashMap<>(128, .75f, true);
    private int pixels;

    private record TextKey(String text, int quarterSize) {}
    private record ShapeKey(int width, int height, int tl, int tr, int br, int bl,
                            int top, int bottom, int border) {}
    private record Texture(ResourceLocation id, int width, int height) {}

    public void text(GuiGraphics graphics, String text, float x, float y, float size, int argb) {
        if (text == null || text.isEmpty() || size <= 0 || (argb >>> 24) == 0) return;
        TextKey key = new TextKey(text, Math.max(1, Math.round(size * 4)));
        Texture texture = textures.get(key);
        if (texture == null) {
            Font font = font(text, key.quarterSize / 4f * RASTER_SCALE);
            int width = Math.max(1, (int) Math.ceil(font.getStringBounds(text, METRICS).getWidth()) + 4);
            int height = (int) Math.ceil(font.getLineMetrics(text, METRICS).getHeight()) + 4;
            texture = create(key, width, height, canvas -> {
                canvas.setColor(Color.WHITE);
                canvas.setFont(font);
                canvas.drawString(text, 2, 2 + font.getLineMetrics(text, METRICS).getAscent());
            });
        }
        draw(graphics, texture, x - 1, y - 1,
                texture.width / (float) RASTER_SCALE, texture.height / (float) RASTER_SCALE, argb);
    }

    public float textWidth(String text, float size) {
        if (text == null || text.isEmpty() || size <= 0) return 0;
        TextKey key = new TextKey(text, Math.max(1, Math.round(size * 4)));
        Float cached = measuredWidths.get(key);
        if (cached != null) return cached;
        float measured = (float) font(text, key.quarterSize / 4f).getStringBounds(text, METRICS).getWidth();
        measuredWidths.put(key, measured);
        if (measuredWidths.size() > 768) {
            var oldest = measuredWidths.entrySet().iterator();
            oldest.next(); oldest.remove();
        }
        return measured;
    }

    public void textCentered(GuiGraphics graphics, String text, float centerX, float y, float size, int argb) {
        text(graphics, text, centerX - textWidth(text, size) / 2, y, size, argb);
    }

    /** Fits one line without letting long translated labels overlap adjacent controls. */
    public String ellipsize(String text, float size, float maxWidth) {
        if (text == null || textWidth(text, size) <= maxWidth) return text == null ? "" : text;
        int end = text.length();
        while (end > 0 && textWidth(text.substring(0, end) + "…", size) > maxWidth) {
            end = text.offsetByCodePoints(end, -1);
        }
        return end == 0 && textWidth("…", size) > maxWidth ? "" : text.substring(0, end) + "…";
    }

    public void roundRect(GuiGraphics graphics, float x, float y, float width, float height,
                          float radius, int fill, int border) {
        if (width * height > 100_000 && width > radius * 2 + 8 && height > radius * 2 + 8) {
            // Large, flat screen panels share one small nine-slice texture even while the window resizes.
            int corner = Math.max(0, Math.round(radius * RASTER_SCALE));
            int baseSize = corner * 2 + 8;
            Texture texture = shapeTexture(baseSize, baseSize, corner, corner, corner, corner, fill, fill, border);
            int margin = corner + 4;
            int[] source = {margin, texture.width - margin * 2, margin};
            float marginSize = margin / (float) RASTER_SCALE;
            float[] widths = {marginSize, width + 2 - marginSize * 2, marginSize};
            float[] heights = {marginSize, height + 2 - marginSize * 2, marginSize};
            int sourceY = 0;
            float targetY = y - 1;
            for (int row = 0; row < 3; row++) {
                int sourceX = 0;
                float targetX = x - 1;
                for (int col = 0; col < 3; col++) {
                    drawRegion(graphics, texture, sourceX, sourceY, source[col], source[row],
                            targetX, targetY, widths[col], heights[row], 0xFFFFFFFF);
                    sourceX += source[col]; targetX += widths[col];
                }
                sourceY += source[row]; targetY += heights[row];
            }
            return;
        }
        gradientRect(graphics, x, y, width, height, radius, fill, fill, border);
    }

    public void gradientRect(GuiGraphics graphics, float x, float y, float width, float height,
                             float radius, int top, int bottom, int border) {
        cornerRect(graphics, x, y, width, height, radius, radius, radius, radius, top, bottom, border);
    }

    /** Independently rounded corners keep the two mouse buttons fitted to the mouse outline. */
    public void cornerRect(GuiGraphics graphics, float x, float y, float width, float height,
                           float topLeft, float topRight, float bottomRight, float bottomLeft,
                           int top, int bottom, int border) {
        if (width <= 0 || height <= 0) return;
        int rasterWidth = Math.max(2, Math.round(width * RASTER_SCALE));
        int rasterHeight = Math.max(2, Math.round(height * RASTER_SCALE));
        Texture texture = shapeTexture(rasterWidth, rasterHeight,
                Math.round(topLeft * RASTER_SCALE), Math.round(topRight * RASTER_SCALE),
                Math.round(bottomRight * RASTER_SCALE), Math.round(bottomLeft * RASTER_SCALE), top, bottom, border);
        draw(graphics, texture, x - width * 2 / rasterWidth, y - height * 2 / rasterHeight,
                width * texture.width / rasterWidth, height * texture.height / rasterHeight, 0xFFFFFFFF);
    }

    private Texture shapeTexture(int rasterWidth, int rasterHeight, int tl, int tr, int br, int bl,
                                 int top, int bottom, int border) {
        ShapeKey key = new ShapeKey(rasterWidth, rasterHeight, tl, tr, br, bl, top, bottom, border);
        Texture texture = textures.get(key);
        if (texture == null) {
            texture = create(key, rasterWidth + 4, rasterHeight + 4, canvas -> {
                float inset = RASTER_SCALE / 2f;
                Path2D shape = corners(2 + inset, 2 + inset, rasterWidth - RASTER_SCALE, rasterHeight - RASTER_SCALE,
                        key.tl, key.tr, key.br, key.bl);
                canvas.setPaint(new GradientPaint(0, 2, new Color(top, true), 0, rasterHeight + 2, new Color(bottom, true)));
                canvas.fill(shape);
                if ((border >>> 24) != 0) {
                    canvas.setColor(new Color(border, true));
                    canvas.setStroke(new BasicStroke(RASTER_SCALE));
                    canvas.draw(shape);
                }
            });
        }
        return texture;
    }

    private static Path2D corners(float x, float y, float w, float h, float tl, float tr, float br, float bl) {
        float factor = Math.min(1, Math.min(Math.min(w / Math.max(1, tl + tr), w / Math.max(1, bl + br)),
                Math.min(h / Math.max(1, tl + bl), h / Math.max(1, tr + br))));
        tl *= factor; tr *= factor; br *= factor; bl *= factor;
        Path2D path = new Path2D.Float();
        path.moveTo(x + tl, y);
        path.lineTo(x + w - tr, y);
        path.quadTo(x + w, y, x + w, y + tr);
        path.lineTo(x + w, y + h - br);
        path.quadTo(x + w, y + h, x + w - br, y + h);
        path.lineTo(x + bl, y + h);
        path.quadTo(x, y + h, x, y + h - bl);
        path.lineTo(x, y + tl);
        path.quadTo(x, y, x + tl, y);
        path.closePath();
        return path;
    }

    private Texture create(Object key, int width, int height, Consumer<Graphics2D> paint) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvas = image.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            canvas.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            paint.accept(canvas);
        } finally {
            canvas.dispose();
        }
        NativeImage nativeImage = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int argb = image.getRGB(x, y);
            nativeImage.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >>> 16) & 255) | ((argb & 255) << 16));
        }
        DynamicTexture dynamic = new DynamicTexture(nativeImage);
        ResourceLocation id = Minecraft.getInstance().getTextureManager().register("pubg_keybindings", dynamic);
        dynamic.setFilter(true, false);
        Texture result = new Texture(id, width, height);
        textures.put(key, result);
        pixels += width * height;
        // The screen draws textures immediately, so evicted entries have no pending GUI vertices.
        while ((textures.size() > MAX_TEXTURES || pixels > MAX_PIXELS) && textures.size() > 1) {
            var oldest = textures.entrySet().iterator();
            Texture evicted = oldest.next().getValue();
            oldest.remove();
            pixels -= evicted.width * evicted.height;
            Minecraft.getInstance().getTextureManager().release(evicted.id);
        }
        return result;
    }

    private static void draw(GuiGraphics graphics, Texture texture, float x, float y, float width, float height, int argb) {
        drawRegion(graphics, texture, 0, 0, texture.width, texture.height, x, y, width, height, argb);
    }

    private static void drawRegion(GuiGraphics graphics, Texture texture, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                   float x, float y, float width, float height, int argb) {
        graphics.flush();
        float[] previous = RenderSystem.getShaderColor().clone();
        boolean blend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f, (argb >>> 24) / 255f);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(width / sourceWidth, height / sourceHeight, 1);
            graphics.blit(texture.id, 0, 0, sourceX, sourceY, sourceWidth, sourceHeight, texture.width, texture.height);
        } finally {
            graphics.pose().popPose();
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
            if (!blend) RenderSystem.disableBlend();
        }
    }

    private static Font font(String text, float size) {
        Font preferred = new Font(FONT_NAME, Font.PLAIN, 1).deriveFont(size);
        return preferred.canDisplayUpTo(text) == -1 ? preferred : new Font(Font.DIALOG, Font.PLAIN, 1).deriveFont(size);
    }

    private static String chooseFont() {
        var available = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : new String[]{"Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "PingFang SC"}) {
            if (available.contains(name)) return name;
        }
        return Font.DIALOG;
    }

    @Override
    public void close() {
        for (Texture texture : textures.values()) Minecraft.getInstance().getTextureManager().release(texture.id);
        textures.clear();
        measuredWidths.clear();
        pixels = 0;
    }
}
