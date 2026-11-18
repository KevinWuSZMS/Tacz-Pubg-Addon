package com.example.examplemod.vitals.client;

import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.originalicons.client.OriginalIconClient;
import com.example.examplemod.pubgsettings.PubgSettingsService;
import com.example.examplemod.localization.Texts;
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
    /** Reference crops share the same geometry: label +4r/+1.05r, key +3.1r/+1.8r from the ring centre. */
    public record MedicineLayout(int radius, float textScale, int labelX, int labelY,
                                 int keyX, int keyY, int keyWidth, int keyHeight, int width, int height) {}
    public static MedicineLayout medicineLayout(int radius, int labelWidth, int keyWidth, int cancelWidth, int lineHeight) {
        float textScale = radius / 24F;
        int center = radius + 1;
        int labelX = center + Math.round(4 * radius), labelY = center + Math.round(1.05F * radius);
        int keyX = center + Math.round(3.1F * radius), keyY = center + Math.round(1.8F * radius);
        int keyHeight = Math.max(1, Math.round(.75F * radius));
        int boxWidth = Math.max(keyHeight, (int) Math.ceil((keyWidth + 6) * textScale));
        int labelRight = labelX + (int) Math.ceil((labelWidth + 4) * textScale);
        int cancelRight = keyX + boxWidth + Math.round(.2F * radius) + (int) Math.ceil((cancelWidth + 2) * textScale);
        return new MedicineLayout(radius, textScale, labelX, labelY, keyX, keyY, boxWidth, keyHeight,
                Math.max(labelRight, cancelRight) + 2, Math.max(keyY + keyHeight + 2,
                labelY + (int) Math.ceil((lineHeight + 4) * textScale)));
    }
    public static HudLayoutModel.Rect medicineBounds(int width, int height, int offset, MedicineLayout layout) {
        float scale = Math.min(1, Math.min(Math.max(1, width - 8) / (float) layout.width(),
                Math.max(1, height - 8) / (float) layout.height()));
        int w = Math.max(1, (int) Math.ceil(layout.width() * scale));
        int h = Math.max(1, (int) Math.ceil(layout.height() * scale));
        int center = Math.round((layout.radius() + 1) * scale);
        return new HudLayoutModel.Rect(Math.clamp(width / 2 - center, 0, Math.max(0, width - w)),
                Math.clamp(height / 2 + offset - center, 0, Math.max(0, height - h)), w, h);
    }
    private static MedicineLayout medicineLayout(Component label, Component cancelKey) {
        var font = Minecraft.getInstance().font;
        return medicineLayout(PubgSettingsService.client().integer("medicine.circle_radius"), font.width(label),
                font.width(cancelKey), font.width(Texts.of("screen.tacz_pubg.medicine.cancel")), font.lineHeight);
    }
    public static HudLayoutModel.Rect bounds(int width, int height, Component label, Component cancelKey, boolean rescue) {
        if (rescue) return bounds(width, height, label);
        return medicineBounds(width, height, PubgSettingsService.client().integer("medicine.circle_offset_y"), medicineLayout(label, cancelKey));
    }
    public static void draw(GuiGraphics graphics, HudLayoutModel.Rect bounds, ItemStack picture, boolean rescue,
                            int remaining, int duration, Component label, Component cancelKey) {
        if (!rescue) {
            drawMedicine(graphics, bounds, picture, remaining, duration, label, cancelKey);
            return;
        }
        Component cancel = Texts.of("screen.tacz_pubg.medicine.action_cancel", cancelKey);
        var settings = PubgSettingsService.client();
        int radius = settings.integer("medicine.circle_radius"), cx = bounds.x() + radius + 1, cy = bounds.y() + radius + 1;
        CombatHudDraw.disk(graphics, cx, cy, radius, settings.color("medicine.circle_background"));
        CombatHudDraw.arc(graphics, cx, cy, radius, settings.number("medicine.circle_line_width"), 0, 360, settings.color("medicine.circle_track"));
        CombatHudDraw.arc(graphics, cx, cy, radius, settings.number("medicine.circle_line_width"), 0,
                progress(remaining, duration) * 360, settings.color("medicine.circle_progress"));
        if (!picture.isEmpty()) OriginalIconClient.drawGui(graphics, picture, cx - 8, cy - 8, 16);
        else if (rescue) CombatHudDraw.plus(graphics, cx - 8, cy - 8, 16, 0x707F817E);
        graphics.pose().pushPose(); graphics.pose().translate(0, 0, 200);
        CombatHudNumbers.drawCentered(graphics, String.format(Locale.ROOT, "%.1f", Math.max(0, remaining) / 20.0), cx, cy - 4.5f, 9, 0xFFFFFFFF);
        graphics.pose().popPose();
        var font = Minecraft.getInstance().font;
        int textX = cx + radius + 9, maxWidth = Math.max(1, bounds.width() - radius * 2 - 12);
        graphics.drawString(font, font.plainSubstrByWidth(label.getString(), maxWidth), textX, cy - 8, 0xFFE4E4E1);
        graphics.drawString(font, font.plainSubstrByWidth(cancel.getString(), maxWidth), textX, cy + 5, 0xFFCACBC7);
    }
    private static void drawMedicine(GuiGraphics g, HudLayoutModel.Rect bounds, ItemStack picture,
                                     int remaining, int duration, Component label, Component cancelKey) {
        var layout = medicineLayout(label, cancelKey);
        var settings = PubgSettingsService.client();
        var font = Minecraft.getInstance().font;
        int r = layout.radius(), center = r + 1;
        float scale = Math.min(bounds.width() / (float) layout.width(), bounds.height() / (float) layout.height());
        g.pose().pushPose();
        try {
            g.pose().translate(bounds.x(), bounds.y(), 0);
            g.pose().scale(scale, scale, 1);
            CombatHudDraw.disk(g, center, center, r, settings.color("medicine.circle_background"));
            CombatHudDraw.arc(g, center, center, r, settings.number("medicine.circle_line_width"), 0, 360, settings.color("medicine.circle_track"));
            CombatHudDraw.arc(g, center, center, r, settings.number("medicine.circle_line_width"), 0,
                    progress(remaining, duration) * 360, settings.color("medicine.circle_progress"));
            int iconSize = Math.round(r * 1.2F);
            if (!picture.isEmpty()) OriginalIconClient.drawGui(g, picture, center - iconSize / 2, center - iconSize / 2, iconSize);
            String seconds = String.format(Locale.ROOT, "%.1f", Math.max(0, remaining) / 20.0);
            float numberHeight = r * .64F, numberTop = center - numberHeight / 2;
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            float outline = Math.max(.35F, r / 56F);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0)
                CombatHudNumbers.drawVisibleCentered(g, seconds, center + dx * outline, numberTop + dy * outline, numberHeight, 0xE020221F);
            CombatHudNumbers.drawVisibleCentered(g, seconds, center, numberTop, numberHeight, 0xFFFFFFFF);
            g.pose().popPose();
            int padding = Math.max(1, Math.round(2 * layout.textScale()));
            g.fill(layout.labelX() - padding, layout.labelY() - padding,
                    layout.labelX() + (int) Math.ceil(font.width(label) * layout.textScale()) + padding,
                    layout.labelY() + (int) Math.ceil(font.lineHeight * layout.textScale()) + padding, 0x40302E29);
            drawText(g, label, layout.labelX(), layout.labelY(), layout.textScale(), 0xFFE0E0DD);
            int x = layout.keyX(), y = layout.keyY(), w = layout.keyWidth(), h = layout.keyHeight();
            g.fill(x, y, x + w, y + h, 0xA020211E);
            g.renderOutline(x, y, w, h, 0xBBD8D8D3);
            drawText(g, cancelKey, x + (w - font.width(cancelKey) * layout.textScale()) / 2,
                    y + (h - font.lineHeight * layout.textScale()) / 2, layout.textScale(), 0xFFFFFFFF);
            drawText(g, Texts.of("screen.tacz_pubg.medicine.cancel"), x + w + Math.round(.2F * r),
                    y + (h - font.lineHeight * layout.textScale()) / 2, layout.textScale(), 0xFFFFFFFF);
        } finally { g.pose().popPose(); }
    }
    private static void drawText(GuiGraphics g, Component text, float x, float y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(Minecraft.getInstance().font, text, 0, 0, color, true);
        g.pose().popPose();
    }
}
