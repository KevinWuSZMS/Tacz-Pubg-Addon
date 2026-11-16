package com.example.examplemod.vitals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Real Bebas Neue glyphs, antialiased offline and linearly sampled at the actual HUD scale. */
public final class CombatHudNumbers {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("tacz_pubg",
            "textures/gui/hud/numbers/bebas_neue.png");
    // These metrics come from build-hud-number-atlas.py and are also recorded in sources.json.
    private static final String GLYPHS = "0123456789%\u221e.\u2014-:/";
    private static final float[] ADVANCES = {26.75f, 26.75f, 26.75f, 26.75f, 26.75f, 26.75f,
            26.75f, 26.75f, 26.75f, 26.75f, 39.5f, 43f, 12.5f, 33.5f, 18f, 12.5f, 26f};
    // Visible alpha >= 32 bounds in the bundled atlas, with its four-pixel padding removed.
    // Advance widths include side bearings, so they alone do not center the visible digits.
    private static final float[] INK_LEFT = {2, 5, 2, 2, 1, 2, 2, 2, 1, 2, 2, 1, 2, 0, 1, 2, 0};
    private static final float[] INK_RIGHT = {25, 21, 25, 25, 26, 25, 25, 25, 25, 25, 38, 42, 10, 34, 17, 10, 26};
    private static final int CELL = 64, COLUMNS = 8, PADDING = 4;
    private static final float CAP_HEIGHT = 48.5f;
    public static final float DEFAULT_HEIGHT = 9;

    private CombatHudNumbers() {}

    private static int glyph(char character) {
        int index = GLYPHS.indexOf(character);
        return index < 0 ? GLYPHS.indexOf('\u2014') : index;
    }

    public static int width(String text) { return width(text, DEFAULT_HEIGHT); }

    public static int width(String text, float height) {
        return (int) Math.ceil(exactWidth(text, height));
    }

    private static float exactWidth(String text, float height) {
        float result = 0;
        for (int i = 0; i < text.length(); i++) result += ADVANCES[glyph(text.charAt(i))];
        return result * height / CAP_HEIGHT;
    }

    public record InkBounds(float left, float right) {
        public float width() { return right - left; }
        public float center() { return (left + right) / 2; }
    }

    public static InkBounds inkBounds(String text, float height) {
        if (text.isEmpty()) return new InkBounds(0, 0);
        float advance = 0, left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < text.length(); i++) {
            int index = glyph(text.charAt(i));
            left = Math.min(left, advance + INK_LEFT[index]);
            right = Math.max(right, advance + INK_RIGHT[index]);
            advance += ADVANCES[index];
        }
        return new InkBounds(left * height / CAP_HEIGHT, right * height / CAP_HEIGHT);
    }

    public static int visibleWidth(String text) { return (int) Math.ceil(inkBounds(text, DEFAULT_HEIGHT).width()); }

    public static void drawVisibleCentered(GuiGraphics graphics, String text, float center, float top, float height, int argb) {
        draw(graphics, text, center - inkBounds(text, height).center(), top, height, argb);
    }

    public static void drawCentered(GuiGraphics graphics, String text, float center, float top, float height, int argb) {
        draw(graphics, text, center - exactWidth(text, height) / 2, top, height, argb);
    }

    public static void draw(GuiGraphics graphics, String text, float left, float top, int argb) {
        draw(graphics, text, left, top, DEFAULT_HEIGHT, argb);
    }

    public static void draw(GuiGraphics graphics, String text, float left, float top, float height, int argb) {
        if (text.isEmpty() || height <= 0 || (argb >>> 24) == 0) return;
        graphics.flush();
        float[] previous = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f, (argb >>> 24) / 255f);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(left, top, 0);
            float scale = height / CAP_HEIGHT;
            graphics.pose().scale(scale, scale, 1);
            for (int i = 0; i < text.length(); i++) {
                int glyph = glyph(text.charAt(i));
                graphics.blit(ATLAS, -PADDING, -PADDING, CELL, CELL,
                        (glyph % COLUMNS) * CELL, (glyph / COLUMNS) * CELL, CELL, CELL, COLUMNS * CELL, 3 * CELL);
                graphics.pose().translate(ADVANCES[glyph], 0, 0);
            }
        } finally {
            graphics.pose().popPose();
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }
}
