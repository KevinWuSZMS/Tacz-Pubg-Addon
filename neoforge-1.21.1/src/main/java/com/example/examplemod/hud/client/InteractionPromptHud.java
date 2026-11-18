package com.example.examplemod.hud.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.vitals.PubgMedicineModel;
import com.example.examplemod.vitals.client.ActionCircleRenderer;
import com.example.examplemod.vitals.client.PubgMedicineHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** All contextual action keys follow the medicine cancel row, including a moved medicine HUD. */
public final class InteractionPromptHud {
    private InteractionPromptHud() {}

    public record Anchor(int x, int y, int keyHeight, float textScale, int gap) {}

    public static Anchor anchor(HudLayoutModel.Rect bounds, ActionCircleRenderer.MedicineLayout layout) {
        float scale = Math.min(bounds.width() / (float) layout.width(), bounds.height() / (float) layout.height());
        return new Anchor(bounds.x() + Math.round(layout.keyX() * scale),
                bounds.y() + Math.round(layout.keyY() * scale), Math.max(1, Math.round(layout.keyHeight() * scale)),
                layout.textScale() * scale, Math.round(.2F * layout.radius() * scale));
    }

    private static Anchor anchor(int width, int height) {
        var label = PubgMedicineHud.actionLabel(PubgMedicineModel.Kind.FIRST_AID_KIT);
        var cancel = Component.keybind("key.tacz_pubg.cancel_use");
        var layout = ActionCircleRenderer.placementLayout();
        var defaults = ActionCircleRenderer.bounds(width, height, label, cancel, false);
        var placed = HudLayoutClient.resolve("medicine_progress", width, height,
                defaults.x(), defaults.y(), defaults.width(), defaults.height());
        return anchor(placed, layout);
    }

    /** A held action owns this row; looking at loot or a door must not draw over its cancel key. */
    public static boolean worldInteractionVisible() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.screen == null && !mc.options.hideGui && !mc.player.isSpectator()
                && !mc.player.isUsingItem()
                && !com.example.examplemod.throwables.client.ThrowableClient.hasPreparedThrowable()
                && !com.example.examplemod.combat.client.CombatInputClient.blocked();
    }

    public static HudLayoutModel.Rect bounds(int width, int height, Component key, Component action) {
        var anchor = anchor(width, height);
        var font = Minecraft.getInstance().font;
        int x = Math.clamp(anchor.x(), 0, Math.max(0, width - anchor.keyHeight()));
        int box = boxWidth(anchor, key, width - x);
        int y = Math.clamp(anchor.y(), 0, Math.max(0, height - anchor.keyHeight()));
        int rowWidth = box + anchor.gap() + (int) Math.ceil(font.width(action) * anchor.textScale());
        return new HudLayoutModel.Rect(x, y, Math.min(Math.max(1, width - x), rowWidth), anchor.keyHeight());
    }

    public static void render(GuiGraphics g, Component key, Component action) {
        render(g, key, action, Component.empty());
    }

    public static void render(GuiGraphics g, Component key, Component action, Component detail) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator()) return;
        draw(g, bounds(g.guiWidth(), g.guiHeight(), key, action), key, action, detail);
    }

    public static void draw(GuiGraphics g, HudLayoutModel.Rect bounds, Component key, Component action, Component detail) {
        var anchor = anchor(g.guiWidth(), g.guiHeight());
        int box = boxWidth(anchor, key, g.guiWidth() - bounds.x());
        drawRow(g, key, action, bounds.x(), bounds.y(), box, bounds.height(), anchor.textScale(), anchor.gap(), bounds.width());
        if (!detail.getString().isEmpty()) {
            int textHeight = (int) Math.ceil(Minecraft.getInstance().font.lineHeight * anchor.textScale());
            int below = bounds.y() + bounds.height() + anchor.gap();
            int y = below + textHeight <= g.guiHeight() ? below : Math.max(0, bounds.y() - textHeight - anchor.gap());
            drawText(g, detail, bounds.x(), y,
                    anchor.textScale(), 0xFFCACBC7, g.guiWidth() - bounds.x());
        }
    }

    private static int boxWidth(Anchor anchor, Component key, int available) {
        return key.getString().isEmpty() ? 0 : Math.min(Math.max(1, available), Math.max(anchor.keyHeight(),
                (int) Math.ceil((Minecraft.getInstance().font.width(key) + 6) * anchor.textScale())));
    }

    public static void drawRow(GuiGraphics g, Component key, Component action, int x, int y,
                               int boxWidth, int boxHeight, float scale, int gap, int width) {
        var font = Minecraft.getInstance().font;
        if (boxWidth > 0) {
            g.fill(x, y, x + boxWidth, y + boxHeight, 0xA020211E);
            g.renderOutline(x, y, boxWidth, boxHeight, 0xBBD8D8D3);
            float keyScale = Math.min(scale, Math.max(1, boxWidth - 4) / (float) Math.max(1, font.width(key)));
            drawText(g, key, x + (boxWidth - font.width(key) * keyScale) / 2,
                    y + (boxHeight - font.lineHeight * keyScale) / 2, keyScale, 0xFFFFFFFF, boxWidth);
        }
        int start = boxWidth == 0 ? 0 : boxWidth + gap;
        drawText(g, action, x + start, y + (boxHeight - font.lineHeight * scale) / 2,
                scale, 0xFFFFFFFF, Math.max(0, width - start));
    }

    private static void drawText(GuiGraphics g, Component text, float x, float y, float scale, int color, int width) {
        if (width <= 0 || scale <= 0) return;
        var font = Minecraft.getInstance().font;
        var visible = font.substrByWidth(Texts.resolve(text), Math.max(1, (int) (width / scale)));
        g.pose().pushPose();
        try {
            g.pose().translate(x, y, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(visible), 0, 0, color, true);
        } finally { g.pose().popPose(); }
    }
}
