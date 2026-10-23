package com.example.examplemod.vitals;

import com.example.examplemod.ExampleMod;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

public final class PubgMedicineItems {
    public static final DeferredItem<PubgMedicineItem> BANDAGE = ExampleMod.ITEMS.registerItem(
            "bandage", p -> new PubgMedicineItem(p, PubgMedicineModel.Kind.BANDAGE), new Item.Properties().stacksTo(32));
    public static final DeferredItem<PubgMedicineItem> FIRST_AID_KIT = ExampleMod.ITEMS.registerItem(
            "first_aid_kit", p -> new PubgMedicineItem(p, PubgMedicineModel.Kind.FIRST_AID_KIT), new Item.Properties().stacksTo(16));
    public static final DeferredItem<PubgMedicineItem> MED_KIT = ExampleMod.ITEMS.registerItem(
            "med_kit", p -> new PubgMedicineItem(p, PubgMedicineModel.Kind.MED_KIT), new Item.Properties().stacksTo(8));
    private PubgMedicineItems() {}
    public static void register() {}
}
