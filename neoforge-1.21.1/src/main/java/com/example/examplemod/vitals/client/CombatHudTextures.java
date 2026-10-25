package com.example.examplemod.vitals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Reference-derived, antialiased masks shared by the live HUD and its layout preview. */
public final class CombatHudTextures {
    public static final ResourceLocation SPRINT = texture("sprint");
    public static final ResourceLocation HEAL = texture("heal");
    private static final ResourceLocation[] BACKPACK = equipment("backpack");
    private static final ResourceLocation[] HELMET = equipment("helmet");
    private static final ResourceLocation[] ARMOR = equipment("armor");

    private CombatHudTextures() {}

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("tacz_pubg", "textures/gui/hud/status/" + name + ".png");
    }

    private static ResourceLocation[] equipment(String kind) {
        return new ResourceLocation[] {texture(kind + "_0"), texture(kind + "_1"),
                texture(kind + "_2"), texture(kind + "_3")};
    }

    public static ResourceLocation backpack(int tier) { return BACKPACK[Math.clamp(tier, 0, 3)]; }
    public static ResourceLocation helmet(int tier) { return HELMET[Math.clamp(tier, 0, 3)]; }
    public static ResourceLocation armor(int tier) { return ARMOR[Math.clamp(tier, 0, 3)]; }

    public static void draw(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0 || (argb >>> 24) == 0) return;
        // Draw queued panels/arcs before the immediate textured quad.
        graphics.flush();
        float[] previous = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f, (argb >>> 24) / 255f);
        try {
            // Full normalized UVs preserve the source mask at every GUI/layout scale.
            graphics.blit(texture, x, y, width, height, 0, 0, 128, 128, 128, 128);
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }
}
