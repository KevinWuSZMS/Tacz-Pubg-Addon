package com.example.examplemod.backpack.client;

import com.example.examplemod.backpack.BackpackPayloads;
import com.example.examplemod.backpack.BackpackRegistration;
import com.example.examplemod.backpack.BackpackWeights;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BackpackClient {
    static final KeyMapping OPEN = new KeyMapping("key.tacz_pubg.backpack", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.tacz_pubg");
    private static final Map<UUID, Integer> TIERS = new HashMap<>();
    private static BackpackPayloads.State current;
    private BackpackClient() {}
    public static int tier(UUID player) { return TIERS.getOrDefault(player, 0); }
    public static BackpackPayloads.State current() { return current; }
    public static String units(int value) { return String.format(Locale.ROOT, "%.1f", value / 1000.0); }

    public static void register(IEventBus modBus) {
        BackpackPayloads.setClient(state -> {
            TIERS.put(state.owner(), state.tier());
            var player = Minecraft.getInstance().player;
            if (player != null && player.getUUID().equals(state.owner())) current = state;
        });
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN));
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(BackpackRegistration.MENU.get(), BackpackScreen::new));
        modBus.addListener((EntityRenderersEvent.AddLayers event) -> {
            for (var skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof PlayerRenderer renderer) renderer.addLayer(new BackpackLayer(renderer));
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            var mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) if (mc.player != null && mc.screen == null)
                PacketDistributor.sendToServer(new BackpackPayloads.Open());
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
            if (event.getScreen() instanceof InventoryScreen screen) {
                event.addListener(Button.builder(Component.literal("B"), button ->
                                PacketDistributor.sendToServer(new BackpackPayloads.Open()))
                        .bounds(Math.min(screen.width - 23, screen.getGuiLeft() + screen.getXSize() + 3),
                                screen.getGuiTop() + 3, 20, 20)
                        .tooltip(Tooltip.create(Component.translatable("container.tacz_pubg.backpack"))).build());
            }
        });
        NeoForge.EVENT_BUS.addListener((ItemTooltipEvent event) -> {
            if (current != null && (current.active() || Minecraft.getInstance().screen instanceof BackpackScreen))
                event.getToolTip().add(Component.translatable("tooltip.tacz_pubg.backpack.weight",
                        units(BackpackWeights.weight(Minecraft.getInstance().player, event.getItemStack()))));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { current = null; TIERS.clear(); });
    }
}
