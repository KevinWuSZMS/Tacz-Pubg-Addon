package com.example.examplemod;

import com.example.examplemod.backup.MapResetService;
import com.example.examplemod.gunbalance.GunBalanceService;
import com.example.examplemod.gunbalance.GunExperimentPayloads;
import com.example.examplemod.lootconfig.LootConfigPayloads;
import com.example.examplemod.lootspawn.GroundLootPayloads;
import com.example.examplemod.lootspawn.DroppedLootService;
import com.example.examplemod.lootspawn.LootRespawnService;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.example.examplemod.spawn.ItemSpawnAreaStick;
import com.example.examplemod.spawn.SpawnAreaPayloads;
import com.example.examplemod.spawn.SpawnAreaService;
import com.example.examplemod.spawn.SpawnRegionConfigPayloads;
import com.example.examplemod.spawn.SpawnStickEvents;
import com.example.examplemod.zones.MapZoneCommands;
import com.example.examplemod.zones.MapZoneConfigPayloads;
import com.example.examplemod.zones.MapOverlayPayloads;
import com.example.examplemod.zones.MapOverlayService;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "tacz_pubg";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredItem<ItemSpawnAreaStick> ITEM_SPAWN_AREA_STICK = ITEMS.registerItem(
            "item_spawn_area_stick", ItemSpawnAreaStick::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<ItemSpawnAreaStick> ITEM_SPAWN_VERY_LOW = ITEMS.registerItem(
            "item_spawn_very_low_flag", properties -> new ItemSpawnAreaStick(properties, com.example.examplemod.lootconfig.LootTier.VERY_LOW), new Item.Properties().stacksTo(1));
    public static final DeferredItem<ItemSpawnAreaStick> ITEM_SPAWN_LOW = ITEMS.registerItem(
            "item_spawn_low_flag", properties -> new ItemSpawnAreaStick(properties, com.example.examplemod.lootconfig.LootTier.LOW), new Item.Properties().stacksTo(1));
    public static final DeferredItem<ItemSpawnAreaStick> ITEM_SPAWN_MEDIUM = ITEMS.registerItem(
            "item_spawn_medium_flag", properties -> new ItemSpawnAreaStick(properties, com.example.examplemod.lootconfig.LootTier.MEDIUM), new Item.Properties().stacksTo(1));
    public static final DeferredItem<ItemSpawnAreaStick> ITEM_SPAWN_VERY_HIGH = ITEMS.registerItem(
            "item_spawn_very_high_flag", properties -> new ItemSpawnAreaStick(properties, com.example.examplemod.lootconfig.LootTier.VERY_HIGH), new Item.Properties().stacksTo(1));
    public static final DeferredItem<com.example.examplemod.zones.PubgConfigMapItem> PUBG_CONFIG_MAP = ITEMS.registerItem(
            "pubg_config_map", com.example.examplemod.zones.PubgConfigMapItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<com.example.examplemod.zones.match.flight.ParachuteItem> PARACHUTE = ITEMS.registerItem(
            "parachute", com.example.examplemod.zones.match.flight.ParachuteItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PUBG_TOOLS =
            CREATIVE_MODE_TABS.register("pubg_tools", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tacz_pubg"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ITEM_SPAWN_AREA_STICK.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ITEM_SPAWN_VERY_LOW.get());
                        output.accept(ITEM_SPAWN_LOW.get());
                        output.accept(ITEM_SPAWN_MEDIUM.get());
                        output.accept(ITEM_SPAWN_AREA_STICK.get());
                        output.accept(ITEM_SPAWN_VERY_HIGH.get());
                        output.accept(PUBG_CONFIG_MAP.get());
                        output.accept(PARACHUTE.get());
                        output.accept(com.example.examplemod.vitals.PubgMedicineItems.BANDAGE.get());
                        output.accept(com.example.examplemod.vitals.PubgMedicineItems.FIRST_AID_KIT.get());
                        output.accept(com.example.examplemod.vitals.PubgMedicineItems.MED_KIT.get());
                        output.accept(com.example.examplemod.backpack.BackpackRegistration.LEVEL_1.get());
                        output.accept(com.example.examplemod.backpack.BackpackRegistration.LEVEL_2.get());
                        output.accept(com.example.examplemod.backpack.BackpackRegistration.LEVEL_3.get());
                        com.example.examplemod.throwables.ThrowableRegistration.ITEMS.values().forEach(item -> output.accept(item.get()));
                        com.example.examplemod.armor.ArmorRegistration.items().forEach(item -> output.accept(item.get()));
                        output.accept(com.example.examplemod.vitals.PubgBoostItems.ENERGY_DRINK.get());
                        output.accept(com.example.examplemod.vitals.PubgBoostItems.PAINKILLER.get());
                        output.accept(com.example.examplemod.vitals.PubgBoostItems.ADRENALINE_SYRINGE.get());
                    })
                    .build());

    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        com.example.examplemod.combat.CombatService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.pubgsettings.PubgSettingsService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.operatorpermissions.OperatorPermissionsService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.vitals.PubgBoostItems.register();
        com.example.examplemod.vitals.PubgMedicineItems.register();
        com.example.examplemod.throwables.ThrowableRegistration.registerItems();
        com.example.examplemod.armor.ArmorRegistration.register(modEventBus, NeoForge.EVENT_BUS);
        com.example.examplemod.inventory.MatchInventoryService.register(modEventBus, NeoForge.EVENT_BUS);
        com.example.examplemod.backpack.BackpackRegistration.register(modEventBus, NeoForge.EVENT_BUS);
        com.example.examplemod.originalicons.OriginalIconService.register(modEventBus, NeoForge.EVENT_BUS);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        LootSpawnRegistration.ENTITIES.register(modEventBus);
        com.example.examplemod.zones.match.flight.FlightRegistration.ENTITIES.register(modEventBus);
        com.example.examplemod.throwables.ThrowableRegistration.ENTITIES.register(modEventBus);
        modEventBus.addListener(com.example.examplemod.throwables.ThrowablePayloads::register);
        modEventBus.addListener(com.example.examplemod.throwables.ThrowableSettingsPayloads::register);
        modEventBus.addListener(com.example.examplemod.zones.match.flight.FlightPayloads::register);
        modEventBus.addListener(SpawnAreaPayloads::register);
        modEventBus.addListener(SpawnRegionConfigPayloads::register);
        modEventBus.addListener(LootConfigPayloads::register);
        modEventBus.addListener(GroundLootPayloads::register);
        modEventBus.addListener(MapZoneConfigPayloads::register);
        modEventBus.addListener(com.example.examplemod.zones.match.MatchConfigPayloads::register);
        modEventBus.addListener(com.example.examplemod.vitals.MatchVitalsPayloads::register);
        modEventBus.addListener(com.example.examplemod.combat.CombatPayloads::register);
        modEventBus.addListener(com.example.examplemod.pubgsettings.PubgSettingsPayloads::register);
        modEventBus.addListener(com.example.examplemod.zones.match.MatchSpectatorPayloads::register);
        modEventBus.addListener(MapOverlayPayloads::register);
        modEventBus.addListener(GunExperimentPayloads::register);
        modEventBus.addListener(com.example.examplemod.visibility.VisibilityPayloads::register);
        SpawnAreaService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.lootspawn.RoundLootService.register(modEventBus, NeoForge.EVENT_BUS);
        LootRespawnService.register(NeoForge.EVENT_BUS);
        DroppedLootService.register(NeoForge.EVENT_BUS);
        MapZoneCommands.register(NeoForge.EVENT_BUS);
        com.example.examplemod.zones.match.MatchService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.zones.match.flight.MatchFlightService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.zones.match.MatchDropService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.zones.match.MatchSpectatorPayloads.registerGame(NeoForge.EVENT_BUS);
        com.example.examplemod.vitals.MatchVitalsService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.vitals.PubgMedicineService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.throwables.ThrowableService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.throwables.ThrowableSettingsService.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(com.example.examplemod.zones.match.bot.PubgPlayerCommands::register);
        MapOverlayService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.zones.match.MatchConfigPayloads.registerGame(NeoForge.EVENT_BUS);
        MapResetService.register(NeoForge.EVENT_BUS);
        GunBalanceService.register(NeoForge.EVENT_BUS);
        com.example.examplemod.visibility.VisibilityService.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(SpawnStickEvents::onLeftClickBlock);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
