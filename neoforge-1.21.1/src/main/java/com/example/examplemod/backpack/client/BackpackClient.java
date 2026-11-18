package com.example.examplemod.backpack.client;

import com.example.examplemod.backpack.BackpackPayloads;
import com.example.examplemod.backpack.BackpackRegistration;
import com.example.examplemod.backpack.BackpackWeights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BackpackClient {
    private static final Map<UUID, Integer> TIERS = new HashMap<>();
    private static BackpackPayloads.State current;
    private BackpackClient() {}
    public static int tier(UUID player) { return TIERS.getOrDefault(player, 0); }
    public static BackpackPayloads.State current() { return current; }
    public static String units(int value) {
        return java.math.BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString();
    }

    public static void register(IEventBus modBus) {
        BackpackPayloads.setClient(state -> {
            TIERS.put(state.owner(), state.tier());
            var player = Minecraft.getInstance().player;
            if (player != null && player.getUUID().equals(state.owner())) current = state;
        });
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(BackpackRegistration.MENU.get(), BackpackScreen::new));
        modBus.addListener((EntityRenderersEvent.AddLayers event) -> {
            for (var skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof PlayerRenderer renderer) renderer.addLayer(new BackpackLayer(renderer));
            }
        });
        NeoForge.EVENT_BUS.addListener((ItemTooltipEvent event) -> {
            if (current != null && (current.active() || Minecraft.getInstance().screen instanceof BackpackScreen))
                event.getToolTip().add(Component.translatable("tooltip.tacz_pubg.backpack.weight",
                        units(BackpackWeights.weight(Minecraft.getInstance().player, event.getItemStack()))));
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            current = null; TIERS.clear();
            com.example.examplemod.backpack.BackpackService.clearClient();
        });
    }
}
