package com.example.examplemod;

import net.minecraft.client.Minecraft;
import com.example.examplemod.spawn.client.SpawnAreaClient;
import com.example.examplemod.spawn.client.SpawnRegionConfigClient;
import com.example.examplemod.zones.client.MapZoneConfigClient;
import com.example.examplemod.lootconfig.client.LootConfigClient;
import com.example.examplemod.lootspawn.client.GroundLootRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = ExampleMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT)
public class ExampleModClient {
    public ExampleModClient(ModContainer container, IEventBus modEventBus) {
        SpawnAreaClient.register();
        SpawnRegionConfigClient.register();
        MapZoneConfigClient.register();
        com.example.examplemod.zones.match.client.MatchConfigClient.register();
        com.example.examplemod.zones.match.client.MatchHudClient.register(modEventBus);
        com.example.examplemod.vitals.client.MatchVitalsHud.register(modEventBus);
        com.example.examplemod.pubgsettings.client.PubgSettingsClient.register();
        com.example.examplemod.combat.client.CombatHudClient.register(modEventBus);
        com.example.examplemod.combat.client.CombatInputClient.register(modEventBus);
        com.example.examplemod.zones.match.client.MatchSpectatorClient.register(modEventBus);
        com.example.examplemod.zones.match.flight.client.FlightClient.register(modEventBus);
        com.example.examplemod.zones.client.MapOverlayClient.register();
        com.example.examplemod.zones.client.MapOverlayHud.register(modEventBus);
        com.example.examplemod.zones.client.visual.BlueZoneVisuals.register(
                com.example.examplemod.zones.client.MapOverlayClient::current);
        modEventBus.addListener(com.example.examplemod.zones.client.visual.BlueZoneShaders::register);
        LootConfigClient.register();
        com.example.examplemod.originalicons.client.OriginalIconClient.register(modEventBus);
        com.example.examplemod.backpack.client.BackpackClient.register(modEventBus);
        com.example.examplemod.throwables.client.ThrowableClient.register(modEventBus);
        com.example.examplemod.throwables.client.ThrowableSettingsClient.register();
        com.example.examplemod.inventory.client.InventoryClient.register();
        com.example.examplemod.gunbalance.client.GunExperimentClient.register();
        com.example.examplemod.visibility.client.VisibilityClient.register();
        modEventBus.addListener(com.example.examplemod.spawn.client.SpawnAreaKeys::registerKeyMappings);
        com.example.examplemod.lootspawn.client.GroundLootClient.register(modEventBus);
        modEventBus.addListener(GroundLootRenderer::registerRenderers);
        modEventBus.addListener(GroundLootRenderer::registerReloadListeners);
        modEventBus.addListener(com.example.examplemod.lootspawn.client.DroppedLootRenderer::registerRenderers);
        com.example.examplemod.lootspawn.client.DroppedLootTransitionClient.register();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                        com.example.examplemod.lootspawn.DroppedLootCatalog.clearClient());
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        ExampleMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        ExampleMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
