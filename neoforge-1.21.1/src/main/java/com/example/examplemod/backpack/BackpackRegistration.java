package com.example.examplemod.backpack;

import com.example.examplemod.ExampleMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BackpackRegistration {
    public static final DeferredItem<BackpackItem> LEVEL_1 = ExampleMod.ITEMS.registerItem(
            "backpack_1", p -> new BackpackItem(p, 1), new Item.Properties().stacksTo(1));
    public static final DeferredItem<BackpackItem> LEVEL_2 = ExampleMod.ITEMS.registerItem(
            "backpack_2", p -> new BackpackItem(p, 2), new Item.Properties().stacksTo(1));
    public static final DeferredItem<BackpackItem> LEVEL_3 = ExampleMod.ITEMS.registerItem(
            "backpack_3", p -> new BackpackItem(p, 3), new Item.Properties().stacksTo(1));
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ExampleMod.MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<BackpackMenu>> MENU =
            MENUS.register("backpack", () -> new MenuType<>(BackpackMenu::new, FeatureFlags.DEFAULT_FLAGS));
    private BackpackRegistration() {}
    public static void register(IEventBus modBus, IEventBus gameBus) {
        MENUS.register(modBus);
        modBus.addListener(BackpackPayloads::register);
        BackpackService.register(gameBus);
    }
}
