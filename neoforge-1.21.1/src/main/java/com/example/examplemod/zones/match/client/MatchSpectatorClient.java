package com.example.examplemod.zones.match.client;

import com.example.examplemod.localization.Texts;
import com.example.examplemod.zones.match.MatchSpectatorPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Restricted death spectating, distinct from Minecraft's unrestricted observer controls. */
public final class MatchSpectatorClient {
    private static final KeyMapping NEXT = new KeyMapping("key.tacz_pubg.spectate_next", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_UP, "key.categories.tacz_pubg");
    private static final KeyMapping PREVIOUS = new KeyMapping("key.tacz_pubg.spectate_previous", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PAGE_DOWN, "key.categories.tacz_pubg");
    private static MatchSpectatorPayloads.Snapshot current;
    private static CameraType savedCamera;
    private static int age;

    private MatchSpectatorClient() {}

    public static void register(IEventBus modBus) {
        MatchSpectatorPayloads.setClientHandler(MatchSpectatorClient::accept);
        modBus.addListener((RegisterKeyMappingsEvent event) -> { event.register(NEXT); event.register(PREVIOUS); });
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath("tacz_pubg", "match_spectator"), (graphics, delta) -> render(graphics)));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> tick());
        NeoForge.EVENT_BUS.addListener((MovementInputUpdateEvent event) -> {
            if (!locked()) return;
            var input = event.getInput();
            input.leftImpulse = input.forwardImpulse = 0;
            input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.InteractionKeyMappingTriggered event) -> {
            if (locked()) { event.setCanceled(true); event.setSwingHand(false); }
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent event) -> {
            if (locked()) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((RenderGuiLayerEvent.Pre event) -> {
            if (locked() && event.getName().equals(VanillaGuiLayers.HOTBAR)) event.setCanceled(true);
        });
    }

    private static void accept(MatchSpectatorPayloads.Snapshot next) {
        if (!next.active()) { clear(); return; }
        current = next;
        age = 0;
        if (next.locked() && savedCamera == null) savedCamera = Minecraft.getInstance().options.getCameraType();
    }

    private static void clear() {
        boolean wasActive = current != null;
        current = null;
        age = 0;
        var mc = Minecraft.getInstance();
        if (wasActive && mc.player != null) mc.setCameraEntity(mc.player);
        if (savedCamera != null) mc.options.setCameraType(savedCamera);
        savedCamera = null;
    }

    private static boolean locked() {
        var mc = Minecraft.getInstance();
        return current != null && current.active() && current.locked() && mc.player != null && mc.level != null;
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (current != null && !mc.isPaused() && ++age > 100) clear();
        while (NEXT.consumeClick()) switchTarget(1);
        while (PREVIOUS.consumeClick()) switchTarget(-1);
        if (!locked()) return;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        // A camera packet can arrive before the destination entity's chunk. Reconcile once tracked.
        var target = current.targetEntityId() < 0 ? mc.player : mc.level.getEntity(current.targetEntityId());
        if (target != null && mc.getCameraEntity() != target) mc.setCameraEntity(target);
        while (mc.options.keyTogglePerspective.consumeClick()) {}
        // Vanilla number keys and middle click open its unrestricted teleport menu.
        for (var hotbar : mc.options.keyHotbarSlots) while (hotbar.consumeClick()) {}
        while (mc.options.keyPickItem.consumeClick()) {}
    }

    private static void switchTarget(int direction) {
        if (!locked() || !current.teammatesOnly() || Minecraft.getInstance().screen != null) return;
        PacketDistributor.sendToServer(new MatchSpectatorPayloads.SwitchTarget(current.sessionId(), direction));
    }

    private static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (!locked() || mc.options.hideGui || mc.screen != null) return;
        String label = current.targetEntityId() < 0
                ? Texts.of("screen.tacz_pubg.spectator.waiting").getString()
                : Texts.of("screen.tacz_pubg.spectator.watching", current.targetName()).getString();
        int width = Math.max(0, graphics.guiWidth() - 20);
        label = mc.font.plainSubstrByWidth(label, width);
        int y = graphics.guiHeight() - 83;
        try (var layout = com.example.examplemod.hud.client.HudLayoutClient.apply(graphics, "spectator",
                (graphics.guiWidth() - width) / 2, y, width, 22)) {
        graphics.drawCenteredString(mc.font, label, graphics.guiWidth() / 2, y, 0xFFFFFF);
        if (current.teammatesOnly()) {
            String keys = Texts.of("screen.tacz_pubg.spectator.switch_hint",
                    PREVIOUS.getTranslatedKeyMessage(), NEXT.getTranslatedKeyMessage()).getString();
            graphics.drawCenteredString(mc.font, mc.font.plainSubstrByWidth(keys, width),
                    graphics.guiWidth() / 2, y + 12, 0xB8BCC2);
        }
        }
    }
}
