package com.example.examplemod.modelscale.client;

import com.example.examplemod.modelscale.ModelScalePayloads;
import com.example.examplemod.modelscale.ModelScaleState;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = "tacz_pubg", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModelScaleClient {
    private ModelScaleClient() {}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        ModelScalePayloads.setClient((state, connection) -> {
            var mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.getConnection().getConnection() != connection) return;
            ModelScaleState.accept(state.revision(), state.settings());
            if (mc.screen instanceof ModelScaleSettingsScreen screen) screen.accept(state);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> ModelScaleState.clear());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> ModelScaleState.clear());
    }
}
