package com.example.examplemod.inventory;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.google.gson.JsonParser;
import java.util.List;

public final class InventorySettingsChecks {
    private static int checks;
    public static void main(String[] args) {
        var initial = InventorySettings.defaults();
        check(!initial.autoEquipmentReplacement() && !initial.autoAttachmentEquip(), "automatic features default off");
        check(initial.pubgDirectEquip(), "PUBG nearby right-click defaults to direct equipment without enabling global automation");
        check(initial.hotbar().equals(List.of("long_gun", "long_gun", "handgun", "melee", "throwable", "free",
                "med_kit", "first_aid_kit", "bandage")), "PC number defaults and free 6");
        var changed = initial.withRole(5, "med_kit");
        check(changed.hotbar().get(6).equals("free") && changed.slot("med_kit") == 5, "role move cannot create two reserved medicine slots");
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            rejects(() -> initial.withRole(slot, "free"), "weapons remain in strict slots");
        }
        changed = changed.withCapacity("ammo:cbrg:ammo_762", .7).withCapacity("item:tacz_pubg:bandage", 2.)
                .withAmmoLimit("cbrg:ammo_762", 400).withAutoEquipmentReplacement(true).withAutoAttachmentEquip(true);
        var retained = changed.withRole(5, "free").withCapacity("item:tacz_pubg:bandage", 3.)
                .withAmmoLimit("cbrg:ammo_762", 500);
        check(retained.autoEquipmentReplacement() && retained.autoAttachmentEquip(),
                "editing hotbar, volume or ammunition keeps both independent toggles");
        check(!retained.withAutoEquipmentReplacement(false).autoEquipmentReplacement()
                && retained.withAutoEquipmentReplacement(false).autoAttachmentEquip(), "equipment toggle cannot erase attachment choice");
        var storeFirst = retained.withPubgDirectEquip(false).withRole(5, "boost")
                .withCapacity("item:minecraft:stone", .5).withAmmoLimit("cbrg:ammo_762", 600)
                .withAutoEquipmentReplacement(false).withAutoAttachmentEquip(false);
        check(!storeFirst.pubgDirectEquip(), "hotbar, capacity, stack and legacy auto edits preserve explicit store-first choice");
        check(retained.withPubgDirectEquip(false).autoEquipmentReplacement()
                        && retained.withPubgDirectEquip(false).autoAttachmentEquip(),
                "PUBG pickup choice does not alter existing automatic equipment or attachment preferences");
        for (boolean equipment : new boolean[] {false, true}) for (boolean attachments : new boolean[] {false, true}) {
            var legacy = new InventorySettings(initial.hotbar(), initial.capacityOverrides(), initial.ammoStackLimits(), equipment, attachments);
            check(legacy.pubgDirectEquip() && legacy.autoEquipmentReplacement() == equipment && legacy.autoAttachmentEquip() == attachments,
                    "legacy constructor adds the PUBG default without overwriting explicit auto flags");
            for (boolean direct : new boolean[] {false, true}) {
                var preference = legacy.withPubgDirectEquip(direct);
                var world = new LootConfigModel.Document(LootConfigModel.General.defaults(), List.of(),
                        LootConfigModel.GunSettings.defaults().withInventory(preference));
                check(LootConfigModel.parse(LootConfigModel.toJson(world)).gunSettings().inventory().equals(preference),
                        "world JSON roundtrip preserves every pickup/automation flag combination");
                String wireJson = new com.google.gson.Gson().toJson(preference);
                check(InventorySettings.parse(JsonParser.parseString(wireJson)).equals(preference),
                        "inventory state JSON preserves the explicit pickup choice");
                var oldJson = JsonParser.parseString(wireJson).getAsJsonObject(); oldJson.remove("pubgDirectEquip");
                check(InventorySettings.parse(oldJson).equals(legacy),
                        "an older inventory record migrates only the missing PUBG pickup preference");
            }
        }
        var document = new LootConfigModel.Document(LootConfigModel.General.defaults(), List.of(),
                LootConfigModel.GunSettings.defaults().withInventory(changed).withOriginalIcons(true).withLowPolyModels(true));
        check(LootConfigModel.parse(LootConfigModel.toJson(document)).equals(document), "all per-item rules survive world JSON");
        check(document.gunSettings().withValue(LootConfigModel.GunSettingCategory.BALANCE, "experimental").inventory().equals(changed),
                "gun settings cannot erase inventory settings");
        var json = JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        json.getAsJsonObject("gunSettings").remove("inventory");
        check(LootConfigModel.parse(json.toString()).gunSettings().inventory().equals(initial), "old saves migrate");
        for (int bad : new int[]{0, -1, 4097, Integer.MAX_VALUE})
            rejects(() -> initial.withAmmoLimit("cbrg:ammo_762", bad), "invalid stack ceiling");
        for (double bad : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 1_000_001})
            rejects(() -> initial.withCapacity("ammo:cbrg:ammo_762", bad), "invalid volume");
        check(initial.withCapacity("item:tacz_pubg:bandage", 0.).capacityOverrides().get("item:tacz_pubg:bandage") == 0, "zero explicit capacity");
        check(changed.withAmmoLimit("cbrg:ammo_762", null).ammoStackLimits().isEmpty(), "reset stack default removes only override");
        rejects(() -> InventorySettings.parse(JsonParser.parseString("{\"ammoStackLimits\":{\"cbrg:ammo_762\":1.5}}")), "fractional cap rejected");
        rejects(() -> InventorySettings.parse(JsonParser.parseString("{\"capacityOverrides\":{\"ammo:cbrg:ammo_762\":\"0.7\"}}")), "string capacity rejected");
        rejects(() -> InventorySettings.parse(JsonParser.parseString("{\"autoEquipmentReplacement\":\"true\"}")), "string auto toggle rejected");
        rejects(() -> InventorySettings.parse(JsonParser.parseString("{\"autoAttachmentEquip\":1}")), "numeric auto toggle rejected");
        for (String invalid : new String[] {"\"true\"", "1", "null", "[]"})
            rejects(() -> InventorySettings.parse(JsonParser.parseString("{\"pubgDirectEquip\":" + invalid + "}")), "nonboolean PUBG pickup preference rejected");
        System.out.println("Inventory setting checks passed: " + checks);
    }
    private static void rejects(Runnable run, String message) {
        try { run.run(); } catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
