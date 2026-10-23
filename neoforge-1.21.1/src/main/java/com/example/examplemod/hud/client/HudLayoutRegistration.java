package com.example.examplemod.hud.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;

/** A normal rebindable client key, usable by any player without a command or operator permission. */
@EventBusSubscriber(modid = "tacz_pubg", value = Dist.CLIENT)
public final class HudLayoutRegistration {
    private static final KeyMapping OPEN = new KeyMapping("key.tacz_pubg.hud_layout", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.tacz_pubg");
    private HudLayoutRegistration() {}

    @EventBusSubscriber(modid = "tacz_pubg", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN); }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new HudLayoutEditorScreen(null));
        }
        XaeroHudBridge.tick();
    }
    @SubscribeEvent public static void previewOnly(RenderGuiEvent.Pre event) {
        // The editor supplies inactive-state samples; keep the normal world/hand render visible
        // while preventing a second set of live HUD elements underneath those samples.
        if (Minecraft.getInstance().screen instanceof HudLayoutEditorScreen) event.setCanceled(true);
    }
}
