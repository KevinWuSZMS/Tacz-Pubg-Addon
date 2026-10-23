package com.example.examplemod.armor;

import com.example.examplemod.ExampleMod;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ArmorRegistration {
    private static final DeferredRegister<ArmorMaterial> MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, ExampleMod.MODID);
    // Empty vanilla layers: ArmorClient renders the actual helmet/vest item geometry.
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> MATERIAL = MATERIALS.register("pubg_armor",
            () -> new ArmorMaterial(Map.of(), 0, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.EMPTY, List.of(), 0, 0));
    public static final DeferredItem<PubgArmorItem> HELMET_1 = item(true, 1);
    public static final DeferredItem<PubgArmorItem> HELMET_2 = item(true, 2);
    public static final DeferredItem<PubgArmorItem> HELMET_3 = item(true, 3);
    public static final DeferredItem<PubgArmorItem> VEST_1 = item(false, 1);
    public static final DeferredItem<PubgArmorItem> VEST_2 = item(false, 2);
    public static final DeferredItem<PubgArmorItem> VEST_3 = item(false, 3);
    private ArmorRegistration() {}
    private static DeferredItem<PubgArmorItem> item(boolean helmet, int tier) {
        return ExampleMod.ITEMS.registerItem((helmet ? "helmet_" : "vest_") + tier,
                p -> new PubgArmorItem(p, helmet, tier), new Item.Properties().stacksTo(1));
    }
    public static void register(IEventBus modBus, IEventBus gameBus) {
        MATERIALS.register(modBus);
        ArmorService.register(gameBus);
    }
    public static List<DeferredItem<PubgArmorItem>> items() {
        return List.of(HELMET_1, HELMET_2, HELMET_3, VEST_1, VEST_2, VEST_3);
    }
}
