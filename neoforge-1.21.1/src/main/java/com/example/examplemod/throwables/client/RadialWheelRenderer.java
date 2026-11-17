package com.example.examplemod.throwables.client;

import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.localization.Texts;
import com.example.examplemod.originalicons.client.OriginalIconClient;
import com.example.examplemod.vitals.client.CombatHudDraw;
import com.example.examplemod.vitals.client.CombatHudNumbers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** The live selector and HUD editor share the same six-sector wheel and native item pictures. */
public final class RadialWheelRenderer {
    private static final int HINT_HEIGHT = 22;

    private RadialWheelRenderer() {}

    public static HudLayoutModel.Rect bounds(int screenWidth, int screenHeight) {
        int radius = (int) Math.ceil(RadialWheelLayout.outerRadius(screenWidth, screenHeight));
        int diameter = radius * 2 + 2;
        return new HudLayoutModel.Rect(screenWidth / 2 - diameter / 2,
                screenHeight / 2 - diameter / 2, diameter, diameter + HINT_HEIGHT);
    }

    public static double radius(HudLayoutModel.Rect bounds) { return Math.max(1, (bounds.width() - 2) * .5); }
    public static double centerX(HudLayoutModel.Rect bounds) { return bounds.x() + bounds.width() * .5; }
    public static double centerY(HudLayoutModel.Rect bounds) { return bounds.y() + bounds.width() * .5; }

    public static void draw(GuiGraphics graphics, HudLayoutModel.Rect bounds, boolean medicine, int hovered, int[] counts) {
        double cx = centerX(bounds), cy = centerY(bounds), radius = radius(bounds);
        double inner = RadialWheelLayout.innerRadius(radius);
        CombatHudDraw.disk(graphics, (int) Math.round(cx), (int) Math.round(cy), (int) Math.round(radius), 0x8A141513);
        boolean selected = hovered >= 0 && hovered < RadialWheelLayout.SLOT_COUNT
                && hovered < counts.length && counts[hovered] > 0;
        if (selected) {
            // Fill the complete slot, including the space behind its icon and quantity badge.
            CombatHudDraw.arc(graphics, cx, cy, radius, radius - inner, hovered * 60 - 30, 60, 0xC2B7A064);
        }
        CombatHudDraw.arc(graphics, cx, cy, radius, .65, 0, 360, 0x608E8C7A);
        CombatHudDraw.arc(graphics, cx, cy, inner, .85, 0, 360, 0xA1C1B68E);
        for (int slot = 0; slot < RadialWheelLayout.SLOT_COUNT; slot++) {
            double boundary = RadialWheelLayout.slotAngle(slot) - Math.PI / RadialWheelLayout.SLOT_COUNT;
            line(graphics, cx + Math.cos(boundary) * inner, cy + Math.sin(boundary) * inner,
                    cx + Math.cos(boundary) * radius, cy + Math.sin(boundary) * radius, .65, 0xA1C1B68E);
        }
        if (selected) {
            // Draw all four edges last so the neutral dividers cannot cover the selected frame.
            int color = 0xFFF3D88F;
            CombatHudDraw.arc(graphics, cx, cy, radius, 1.5, hovered * 60 - 30, 60, color);
            CombatHudDraw.arc(graphics, cx, cy, inner + 1.5, 1.5, hovered * 60 - 30, 60, color);
            for (int side = -1; side <= 1; side += 2) {
                double boundary = RadialWheelLayout.slotAngle(hovered) + side * Math.PI / RadialWheelLayout.SLOT_COUNT;
                line(graphics, cx + Math.cos(boundary) * inner, cy + Math.sin(boundary) * inner,
                        cx + Math.cos(boundary) * radius, cy + Math.sin(boundary) * radius, 1.5, color);
            }
        }
        // Finish translucent geometry before textures so the icon blits cannot reorder the wheel underneath them.
        graphics.flush();
        int iconSize = Math.max(16, (int) Math.round(radius * .32));
        int badgeRadius = Math.max(6, (int) Math.round(radius * .075));
        float numberHeight = (float) Math.max(8, radius * .095);
        var actions = RadialWheelLayout.actions(medicine);
        for (int slot = 0; slot < RadialWheelLayout.SLOT_COUNT; slot++) {
            int count = slot < counts.length ? Math.max(0, counts[slot]) : 0;
            if (count == 0) continue;
            double angle = RadialWheelLayout.slotAngle(slot);
            int x = (int) Math.round(cx + Math.cos(angle) * radius * .72);
            int y = (int) Math.round(cy + Math.sin(angle) * radius * .72);
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("tacz_pubg", actions.get(slot)));
            OriginalIconClient.drawGui(graphics, new ItemStack(item), x - iconSize / 2, y - iconSize / 2, iconSize);
            int badgeX = x + (int) Math.round(iconSize * .36), badgeY = y + (int) Math.round(iconSize * .36);
            CombatHudDraw.disk(graphics, badgeX, badgeY, badgeRadius, 0xC4131411);
            String quantity = Integer.toString(count);
            float fittedHeight = Math.min(numberHeight, numberHeight * (badgeRadius * 1.7f)
                    / Math.max(1, CombatHudNumbers.width(quantity, numberHeight)));
            CombatHudNumbers.drawCentered(graphics, quantity, badgeX, badgeY - fittedHeight / 2, fittedHeight, 0xFFE9E7DA);
        }
        var font = Minecraft.getInstance().font;
        String hint = Texts.of("screen.tacz_pubg.throwable.wheel_hint").getString();
        float hintScale = Math.min(1, (bounds.width() - 4f) / Math.max(1, font.width(hint)));
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy + radius + 10, 0);
        graphics.pose().scale(hintScale, hintScale, 1);
        graphics.drawCenteredString(font, hint, 0, 0, 0xFFE3E1D7);
        graphics.pose().popPose();
    }

    private static void line(GuiGraphics graphics, double x1, double y1, double x2, double y2, double width, int color) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        if (length == 0) return;
        double nx = -(y2 - y1) * width / (length * 2), ny = (x2 - x1) * width / (length * 2);
        var vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        var matrix = graphics.pose().last().pose();
        vertices.addVertex(matrix, (float) (x1 + nx), (float) (y1 + ny), 0).setColor(color);
        vertices.addVertex(matrix, (float) (x2 + nx), (float) (y2 + ny), 0).setColor(color);
        vertices.addVertex(matrix, (float) (x2 - nx), (float) (y2 - ny), 0).setColor(color);
        vertices.addVertex(matrix, (float) (x1 - nx), (float) (y1 - ny), 0).setColor(color);
    }
}
