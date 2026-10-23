package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.inventory.InventoryPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class InventoryClient {
    private InventoryClient() {}
    public static void register() {
        InventoryPayloads.setClient((value, connection) -> {
            var current = Minecraft.getInstance().getConnection();
            if (current != null && current.getConnection() == connection)
                InventoryConfig.accept(value.revision(), value.active(), value.settings(), value.equipmentLimit());
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> InventoryConfig.clearClient());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> InventoryConfig.clearClient());
    }
}
