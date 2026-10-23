package com.example.examplemod.vitals;

import com.example.examplemod.ExampleMod;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

public final class PubgBoostItems {
    public static final DeferredItem<PubgBoostItem> ENERGY_DRINK = ExampleMod.ITEMS.registerItem(
            "energy_drink", properties -> new PubgBoostItem(properties, PubgMedicineModel.Kind.ENERGY_DRINK), new Item.Properties().stacksTo(16));
    public static final DeferredItem<PubgBoostItem> PAINKILLER = ExampleMod.ITEMS.registerItem(
            "painkiller", properties -> new PubgBoostItem(properties, PubgMedicineModel.Kind.PAINKILLER), new Item.Properties().stacksTo(16));
    public static final DeferredItem<PubgBoostItem> ADRENALINE_SYRINGE = ExampleMod.ITEMS.registerItem(
            "adrenaline_syringe", properties -> new PubgBoostItem(properties, PubgMedicineModel.Kind.ADRENALINE_SYRINGE), new Item.Properties().stacksTo(8));
    private PubgBoostItems() {}
    public static void register() { /* Force registration before the item registry is attached. */ }
}
