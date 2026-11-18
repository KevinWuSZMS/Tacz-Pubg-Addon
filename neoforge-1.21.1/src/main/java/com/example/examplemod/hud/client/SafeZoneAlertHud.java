package com.example.examplemod.hud.client;

import com.example.examplemod.localization.Texts;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class SafeZoneAlertHud {
    private static final int YELLOW = 0xF5ED69;
    private static final float CENTER_Y = 0.63F;
    private static final float LINE_HEIGHT_FRACTION = 0.034F;

    private SafeZoneAlertHud() {}

    public static void draw(GuiGraphics graphics, Component text, int alertTicks) {
        Component bold = text.copy().withStyle(ChatFormatting.BOLD);
        var bounds = bounds(bold, graphics.guiWidth(), graphics.guiHeight());
        var placed = HudLayoutClient.resolveRect(graphics, "match_alert",
                bounds.x(), bounds.y(), bounds.width(), bounds.height());
        int alpha = 255 * Math.clamp(alertTicks, 0, 15) / 15;
        drawLabel(graphics, bold, placed, alpha);
    }

    private static Component sample() {
        return Texts.of("screen.tacz_pubg.pubg_hud.restricting").withStyle(ChatFormatting.BOLD);
    }

    static HudLayoutModel.Rect previewBounds(int width, int height) {
        return bounds(sample(), width, height);
    }

    static void drawPreview(GuiGraphics graphics, HudLayoutModel.Rect rect) {
        drawLabel(graphics, sample(), rect, 255);
    }

    private static HudLayoutModel.Rect bounds(Component text, int screenWidth, int screenHeight) {
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text) + 1; // Include the shadow in placement and width fitting.
        float scale = Math.min(screenHeight * LINE_HEIGHT_FRACTION / font.lineHeight,
                Math.max(1, screenWidth - 12) / (float) textWidth);
        int width = Math.max(1, Math.round(textWidth * scale));
        int height = Math.max(1, Math.round(font.lineHeight * scale));
        return new HudLayoutModel.Rect((screenWidth - width) / 2,
                Math.round(screenHeight * CENTER_Y - height / 2F), width, height);
    }

    private static void drawLabel(GuiGraphics graphics, Component text, HudLayoutModel.Rect rect, int alpha) {
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text) + 1;
        float scale = Math.min(rect.width() / (float) textWidth, rect.height() / (float) font.lineHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(rect.x() + (rect.width() - textWidth * scale) / 2F,
                rect.y() + (rect.height() - font.lineHeight * scale) / 2F, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, (alpha << 24) | YELLOW, true);
        graphics.pose().popPose();
    }
}
