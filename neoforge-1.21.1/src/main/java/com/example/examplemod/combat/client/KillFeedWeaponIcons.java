package com.example.examplemod.combat.client;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.vitals.client.ThrowableHudIcons;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.api.TimelessAPI;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Feed-only art. Gun pack HUD and inventory textures keep their original orientation. */
final class KillFeedWeaponIcons {
    private static final String BASE = "textures/gui/hud/kill_feed/";
    private static final ResourceLocation GUNS = asset("guns.png");
    private static Map<ResourceLocation, GunIcon> gunSprites = Map.of();

    private KillFeedWeaponIcons() {}

    private record Sprite(int u, int v, int width, int height, int textureWidth, int textureHeight) {}

    record GunIcon(ResourceLocation texture, Sprite sprite, boolean mirrored) {
        void draw(GuiGraphics graphics, int x, int y, int width, int height, int argb) {
            if (!mirrored) {
                KillFeedWeaponIcons.draw(graphics, texture, sprite, x, y, width, height, argb);
                return;
            }
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(2 * x + width, 0, 0);
                graphics.pose().scale(-1, 1, 1);
                KillFeedWeaponIcons.draw(graphics, texture, sprite, x, y, width, height, argb);
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    private static ResourceLocation asset(String name) {
        return ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, BASE + name);
    }

    static void register(IEventBus bus) {
        bus.addListener((RegisterClientReloadListenersEvent event) ->
                event.registerReloadListener((ResourceManagerReloadListener) KillFeedWeaponIcons::loadAtlas));
    }

    private static void loadAtlas(ResourceManager manager) {
        try (var reader = manager.openAsReader(asset("guns.json"))) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            int width = root.get("width").getAsInt();
            int height = root.get("height").getAsInt();
            var sprites = new HashMap<ResourceLocation, GunIcon>();
            for (var entry : root.getAsJsonObject("guns").entrySet()) {
                var rect = entry.getValue().getAsJsonArray();
                var sprite = new Sprite(rect.get(0).getAsInt(), rect.get(1).getAsInt(),
                        rect.get(2).getAsInt(), rect.get(3).getAsInt(), width, height);
                sprites.put(ResourceLocation.parse(entry.getKey()), new GunIcon(GUNS, sprite, false));
            }
            gunSprites = Map.copyOf(sprites);
        } catch (IOException | RuntimeException ex) {
            gunSprites = Map.of();
            ExampleMod.LOGGER.warn("Could not load kill feed gun atlas", ex);
        }
    }

    static GunIcon gunIcon(ResourceLocation gunId) {
        if (gunId == null) return null;
        var icon = gunSprites.get(gunId);
        if (icon != null) return icon;
        // Third-party packs installed after the atlas was built can supply their own TaCZ HUD.
        return TimelessAPI.getClientGunIndex(gunId)
                .map(index -> index.getDefaultDisplay())
                .map(display -> display.getHUDTexture())
                .filter(id -> Minecraft.getInstance().getResourceManager().getResource(id).isPresent())
                .map(id -> new GunIcon(id, new Sprite(0, 0, 39, 13, 39, 13), true))
                .orElse(null);
    }

    static boolean drawThrowable(GuiGraphics graphics, ResourceLocation item, int x, int y, int width, int height, int argb) {
        var icon = ThrowableHudIcons.texture(item, null);
        if (icon == null) return false;
        draw(graphics, icon, new Sprite(0, 0, 96, 96, 96, 96), x, y, width, height, argb);
        return true;
    }

    private static void draw(GuiGraphics graphics, ResourceLocation texture, Sprite sprite,
                             int x, int y, int width, int height, int argb) {
        float scale = Math.min((float) width / sprite.width, (float) height / sprite.height);
        int w = Math.max(1, Math.round(scale * sprite.width));
        int h = Math.max(1, Math.round(scale * sprite.height));
        graphics.flush();
        var previous = RenderSystem.getShaderColor().clone();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f, (argb >>> 24) / 255f);
        try {
            graphics.blit(texture, x + (width - w) / 2, y + (height - h) / 2, w, h,
                    sprite.u, sprite.v, sprite.width, sprite.height, sprite.textureWidth, sprite.textureHeight);
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }
}
