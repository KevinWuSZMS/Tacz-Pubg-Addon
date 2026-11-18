package com.example.examplemod.combat.client;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.throwables.ThrowableKind;
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
    private static final ResourceLocation GENERIC_GUN = ResourceLocation.parse("tacz:m4a1");
    private static final Map<ResourceLocation, ResourceLocation> THROWABLES = throwableIcons();
    private static Map<ResourceLocation, Sprite> gunSprites = Map.of();

    private KillFeedWeaponIcons() {}

    private record Sprite(int u, int v, int width, int height, int textureWidth, int textureHeight) {}

    private static ResourceLocation asset(String name) {
        return ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, BASE + name);
    }

    private static Map<ResourceLocation, ResourceLocation> throwableIcons() {
        var icons = new HashMap<ResourceLocation, ResourceLocation>();
        for (var kind : ThrowableKind.values()) {
            var item = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, kind.id);
            icons.put(item, asset("throwables/" + kind.id + ".png"));
        }
        icons.put(ResourceLocation.parse("lrtactical:m67"), asset("throwables/frag_grenade.png"));
        icons.put(ResourceLocation.parse("lrtactical:rgn"), asset("throwables/frag_grenade.png"));
        icons.put(ResourceLocation.parse("lrtactical:flash_grenade"), asset("throwables/stun_grenade.png"));
        for (var name : new String[] {"smoke_grenade", "molotov", "c4"}) {
            icons.put(ResourceLocation.fromNamespaceAndPath("lrtactical", name), asset("throwables/" + name + ".png"));
        }
        return Map.copyOf(icons);
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
            var sprites = new HashMap<ResourceLocation, Sprite>();
            for (var entry : root.getAsJsonObject("guns").entrySet()) {
                var rect = entry.getValue().getAsJsonArray();
                sprites.put(ResourceLocation.parse(entry.getKey()), new Sprite(rect.get(0).getAsInt(),
                        rect.get(1).getAsInt(), rect.get(2).getAsInt(), rect.get(3).getAsInt(), width, height));
            }
            gunSprites = Map.copyOf(sprites);
        } catch (IOException | RuntimeException ex) {
            gunSprites = Map.of();
            ExampleMod.LOGGER.warn("Could not load kill feed gun atlas", ex);
        }
    }

    static boolean drawGun(GuiGraphics graphics, ResourceLocation gunId, int x, int y, int width, int height, int argb) {
        if (gunId == null) return false;
        var sprite = gunSprites.get(gunId);
        if (sprite == null) {
            // Third-party packs installed after the atlas was built can supply their own TaCZ HUD.
            var live = TimelessAPI.getClientGunIndex(gunId)
                    .map(index -> index.getDefaultDisplay().getHUDTexture())
                    .filter(id -> Minecraft.getInstance().getResourceManager().getResource(id).isPresent());
            if (live.isPresent()) {
                graphics.pose().pushPose();
                try {
                    graphics.pose().translate(2 * x + width, 0, 0);
                    graphics.pose().scale(-1, 1, 1);
                    draw(graphics, live.get(), new Sprite(0, 0, 39, 13, 39, 13), x, y, width, height, argb);
                } finally {
                    graphics.pose().popPose();
                }
                return true;
            }
            sprite = gunSprites.get(GENERIC_GUN);
            if (sprite == null) return false;
        }
        draw(graphics, GUNS, sprite, x, y, width, height, argb);
        return true;
    }

    static boolean drawThrowable(GuiGraphics graphics, ResourceLocation item, int x, int y, int width, int height, int argb) {
        var icon = item == null ? null : THROWABLES.get(item);
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
