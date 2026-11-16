package com.example.examplemod.keybindings.client;

import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.EventPriority;
import com.example.examplemod.keybindings.mixin.OptionsParentAccessor;

public final class BindingUiClient {
    private BindingUiClient() {}
    public static void register() { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BindingUiClient::opening); }
    private static void opening(ScreenEvent.Opening event) {
        // Controlling replaces the same original event at NORMAL priority. Resolve the final target last.
        if (event.getScreen() instanceof KeyBindsScreen screen) {
            event.setNewScreen(new BindingScreen(((OptionsParentAccessor)screen).tacz_pubg$parent()));
        }
    }
}
