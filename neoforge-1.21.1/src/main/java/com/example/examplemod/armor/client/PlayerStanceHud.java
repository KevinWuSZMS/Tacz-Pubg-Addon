package com.example.examplemod.armor.client;

import com.example.examplemod.hud.client.HudLayoutClient;
import com.example.examplemod.hud.client.HudLayoutModel;
import com.example.examplemod.vitals.client.CombatHudTextures;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;

/** The first-person body indicator follows the equipped status row without changing its contents. */
public final class PlayerStanceHud {
    public static final String HUD_ID = "player_stance";
    public enum Stance {
        // Source alpha>=128 bounds are recorded in stance/sources.json. Share a visual foot baseline.
        STANDING("standing", 658F / 1254, 1109F / 1254, 1),
        CROUCHING("crouching", 666.5F / 1254, 930F / 1254, 1.17F),
        PRONE("prone", 628F / 1254, 776F / 1254, 1);
        private final ResourceLocation texture;
        private final float centerX, bottom, scale;
        Stance(String name, float centerX, float bottom, float scale) {
            texture = ResourceLocation.fromNamespaceAndPath("tacz_pubg", "textures/gui/hud/stance/" + name + ".png");
            this.centerX = centerX; this.bottom = bottom; this.scale = scale;
        }
        public ResourceLocation texture() { return texture; }
    }
    private PlayerStanceHud() {}

    /** Read the collision-resolved body pose, not the requested movement mode or a held key. */
    public static Stance stance(Pose pose, boolean swimming) {
        if (swimming) return null;
        return switch (pose) {
            case STANDING -> Stance.STANDING;
            case CROUCHING -> Stance.CROUCHING;
            case SWIMMING -> Stance.PRONE; // Both TaCZ crawl and our ground prone use this body pose.
            default -> null;
        };
    }
    public static HudLayoutModel.Rect bounds(int width, int height, HudLayoutModel.Rect equipment) {
        int size = Math.min(Math.min(width, height), Math.max(1, Math.round(equipment.height() * 1.2F)));
        int gap = Math.max(2, Math.round(equipment.height() * .1F));
        int x = equipment.x() - gap - size;
        int y = equipment.y() + (equipment.height() - size) / 2;
        // A panel deliberately dragged against the left edge leaves no left-hand space.
        // Keep the indicator above it rather than covering ammo or equipment cells.
        if (x < 0) {
            x = equipment.x(); y = equipment.y() - gap - size;
            if (y < 0) y = equipment.y() + equipment.height() + gap;
        }
        return new HudLayoutModel.Rect(Math.clamp(x, 0, Math.max(0, width - size)),
                Math.clamp(y, 0, Math.max(0, height - size)), size, size);
    }
    public static HudLayoutModel.Rect previewBounds(int width, int height) {
        return bounds(width, height, ArmorClient.equipmentBounds(width, height));
    }
    public static void preview(GuiGraphics graphics, HudLayoutModel.Rect bounds) {
        draw(graphics, bounds, Stance.STANDING);
    }
    public static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.options.hideGui || !MatchVitalsHud.active() || !player.isAlive() || player.isSpectator()
                || !mc.options.getCameraType().isFirstPerson() || mc.getCameraEntity() != player
                || player.getAbilities().flying || player.isPassenger() || player.isFallFlying()) return;
        Stance stance = stance(player.getPose(), player.isSwimming());
        if (stance == null) return;
        var bounds = previewBounds(graphics.guiWidth(), graphics.guiHeight());
        var placed = HudLayoutClient.resolveRect(graphics, HUD_ID, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        draw(graphics, placed, stance);
    }
    private static void draw(GuiGraphics graphics, HudLayoutModel.Rect bounds, Stance stance) {
        graphics.pose().pushPose();
        graphics.pose().translate(bounds.x() + (.5F - stance.centerX * stance.scale) * bounds.width(),
                bounds.y() + (.85F - stance.bottom * stance.scale) * bounds.height(), 0);
        graphics.pose().scale(stance.scale, stance.scale, 1);
        CombatHudTextures.draw(graphics, stance.texture(), 0, 0, bounds.width(), bounds.height(), 0xFFFFFFFF);
        graphics.pose().popPose();
    }
}
