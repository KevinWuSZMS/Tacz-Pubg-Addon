package com.example.examplemod.vitals.client;

import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** One medicine/rescue ring: grey disk, action picture, centred seconds, clockwise white progress. */
public final class ActionCircleRenderer {
    private ActionCircleRenderer() {}
    public static double progress(int remaining, int duration) {
        return duration <= 0 ? 0 : Math.clamp(1 - remaining / (double) duration, 0, 1);
    }
    public static HudLayoutModel.Rect bounds(int width, int height, Component label) {
        int radius = PubgSettingsService.client().integer("medicine.circle_radius");
        int offset = PubgSettingsService.client().integer("medicine.circle_offset_y");
        int tail = Math.min(Math.max(20, width / 2 - radius - 12), Minecraft.getInstance().font.width(label) + 12);
        return new HudLayoutModel.Rect(Math.max(0, width / 2 - radius - 1),
                Math.clamp(height / 2 + offset - radius - 1, 0, Math.max(0, height - radius * 2 - 3)), radius * 2 + 3 + tail, radius * 2 + 3);
    }
    public static void draw(GuiGraphics graphics, HudLayoutModel.Rect bounds, ItemStack picture, boolean rescue,
                            int remaining, int duration, Component label, Component cancel) {
        var settings = PubgSettingsService.client();
        int radius = settings.integer("medicine.circle_radius"), cx = bounds.x() + radius + 1, cy = bounds.y() + radius + 1;
        CombatHudDraw.disk(graphics, cx, cy, radius, settings.color("medicine.circle_background"));
        CombatHudDraw.arc(graphics, cx, cy, radius, settings.number("medicine.circle_line_width"), 0, 360, settings.color("medicine.circle_track"));
        CombatHudDraw.arc(graphics, cx, cy, radius, settings.number("medicine.circle_line_width"), 0,
                progress(remaining, duration) * 360, settings.color("medicine.circle_progress"));
        if (!picture.isEmpty()) graphics.renderItem(picture, cx - 8, cy - 8);
        else if (rescue) CombatHudDraw.plus(graphics, cx - 8, cy - 8, 16, 0x707F817E);
        graphics.pose().pushPose(); graphics.pose().translate(0, 0, 200);
        CombatHudNumbers.drawCentered(graphics, String.format(Locale.ROOT, "%.1f", Math.max(0, remaining) / 20.0), cx, cy - 4.5f, 9, 0xFFFFFFFF);
        graphics.pose().popPose();
        var font = Minecraft.getInstance().font;
        int textX = cx + radius + 9, maxWidth = Math.max(1, bounds.width() - radius * 2 - 12);
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), maxWidth), textX, cy - 8, 0xFFE4E4E1);
        graphics.drawString(font, font.plainSubstrByWidth(cancel.getString(), maxWidth), textX, cy + 5, 0xFFCACBC7);
    }
}
