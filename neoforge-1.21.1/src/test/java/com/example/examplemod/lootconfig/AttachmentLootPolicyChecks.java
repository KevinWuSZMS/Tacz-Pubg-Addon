package com.example.examplemod.lootconfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.examplemod.lootconfig.AttachmentLootPolicy.Info;
import com.example.examplemod.lootconfig.AttachmentLootPolicy.Snapshot;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettingCategory;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;

/** Pure inventory metadata and generation-pool policy; no live TaCZ or world is required. */
public final class AttachmentLootPolicyChecks {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static Entry entry(String id, double weight) { return new Entry("attachment", id, 1, 1, weight); }
    private static double weight(Entry entry, GunSettings settings, Info info) {
        return AttachmentLootPolicy.effectiveWeight(entry, settings, info);
    }

    public static void main(String[] args) {
        GunSettings initial = GunSettings.defaults();
        GunSettings all = initial;
        for (var category : GunSettingCategory.values()) all = all.withValue(category, LootConfigModel.EXPERIMENTAL);
        check(AttachmentLootPolicy.specialistThreshold(424) == 42, "Ten percent uses current gun count, rounded down");
        check(AttachmentLootPolicy.isSpecialist("stock", 42, 424), "Inclusive rare threshold");
        check(!AttachmentLootPolicy.isSpecialist("stock", 43, 424), "Above threshold is common");
        check(!AttachmentLootPolicy.isSpecialist("stock", 0, 424), "Zero support is not a usable specialist");
        check(!AttachmentLootPolicy.isSpecialist("laser", 1, 424), "Laser never enters specialist companion family");
        check(AttachmentLootPolicy.specialistThreshold(100) == 10, "Threshold changes with loaded inventory");
        check(AttachmentLootPolicy.semanticCategory("cbrg:laser_sight", "grip").equals("laser"), "CBRG physical GRIP is semantic LASER");
        check(AttachmentLootPolicy.semanticCategory("suffuse:pistollaser", "grip").equals("laser"), "Second known GRIP laser isolated");
        check(AttachmentLootPolicy.semanticCategory("tacz:laser_peq15", "laser").equals("laser"), "Native LASER remains LASER");
        check(AttachmentLootPolicy.semanticCategory("classicr:sight_canted_laser", "scope").equals("scope"), "No name substring classification");
        check(AttachmentLootPolicy.semanticCategory("suffuse:grip_flashlight", "grip").equals("grip"), "Flashlight is not invented as a laser");
        check(AttachmentLootPolicy.APPROVED_SCOPES.size() == 9 && AttachmentLootPolicy.APPROVED_SCOPES.contains("cbrg:canted_sight"), "Existing approved historical nine scopes");
        check(AttachmentLootPolicy.APPROVED_MAGAZINES.size() == 9, "Only the rebuilt nine magazines");
        check(AttachmentLootPolicy.APPROVED_LASERS.equals(Set.of("cbrg:laser_sight")), "Only selected CBRG laser");
        check(AttachmentLootPolicy.APPROVED_AMMO.size() == 18, "Existing approved supply target set");

        Info rare = new Info("stock", 4, true), common = new Info("stock", 129, false), zero = new Info("stock", 0, false);
        check(weight(entry("pack:special_stock", 1), all, rare) == .01, "Rare cap");
        check(weight(entry("pack:special_stock", .003), all, rare) == .003, "Cap never increases a smaller positive base");
        check(weight(entry("pack:special_stock", 0), all, rare) == 0, "User zero never resurrected");
        check(weight(entry("pack:common_stock", 4), all, common) == 4, "Common non-CBRG attachment retains base weight");
        check(weight(entry("pack:no_support", 1), all, zero) == 0, "Unusable experiment attachment suppressed");
        check(weight(entry("pack:no_support", 1), initial, zero) == 1, "Initial preserves original selection weight");
        check(weight(entry("pack:special_stock", 1), initial, rare) == 1, "Initial does not alter rare weights");
        check(weight(entry("pack:unloaded", 1), all, null) == 0, "Unloaded attachment never generated");
        check(weight(new Entry("attachment", "pack:special_stock", 0, 0, 1), all, rare) == 0, "Zero count entry cannot generate");
        check(weight(entry("cbrg:red_dot", 1), all, new Info("scope", 300, false)) == 1, "Selected scope retained");
        check(weight(entry("tacz:sight_552", 1), all, new Info("scope", 300, false)) == 0, "Other scope filtered");
        check(weight(entry("cbrg:extended_mag_rifle", 1), all, new Info("magazine", 188, false)) == 0, "Old magazine not in new nine");
        check(weight(entry("tacz_pubg_workbench:quickdraw_mag_rifle", 1), all, new Info("magazine", 182, false)) == 1, "New magazine retained");
        Info laser = new Info("laser", 198, false);
        GunSettings gripOnly = initial.withValue(GunSettingCategory.GRIP, LootConfigModel.EXPERIMENTAL);
        GunSettings laserOnly = initial.withValue(GunSettingCategory.LASER, LootConfigModel.EXPERIMENTAL);
        check(weight(entry("suffuse:pistollaser", 1), gripOnly, laser) == 1, "Grip experiment does not filter semantic laser");
        check(weight(entry("suffuse:pistollaser", 1), laserOnly, laser) == 0, "Laser experiment filters other semantic lasers");
        check(weight(entry("cbrg:laser_sight", 1), laserOnly, laser) == 1, "Selected semantic GRIP laser retained");
        Entry approvedAmmo = new Entry("ammo", "cbrg:ammo_556", 30, 30, 0);
        check(weight(approvedAmmo, all, new Info("ammo", 100, false)) == 0, "Approved ammo default zero stays zero");
        check(weight(new Entry("ammo", "tacz:556x45", 30, 30, 1), all, new Info("ammo", 1, false)) == 0, "Old independent ammo outside approved targets filtered");
        check(weight(new Entry("gun", "pack:rare_gun", 1, 1, 2), all, null) == 2, "Balance experiment does not filter any gun");
        check(weight(new Entry("item", "minecraft:apple", 1, 1, 3), all, null) == 3, "Ordinary items untouched");

        Map<String, Info> infos = new HashMap<>();
        infos.put("pack:special_stock", rare); infos.put("pack:common_stock", common); infos.put("pack:no_support", zero);
        infos.put("pack:special_muzzle", new Info("muzzle", 2, true));
        infos.put("pack:special_grip", new Info("grip", 1, true)); infos.put("cbrg:laser_sight", new Info("laser", 198, false));
        infos.put("pack:disabled", rare); infos.put("pack:zero_count", rare); infos.put("pack:other_gun", rare);
        Map<String, Set<String>> support = new HashMap<>();
        for (String id : infos.keySet()) support.put(id, Set.of("pack:gun_a"));
        support.put("pack:no_support", Set.of()); support.put("pack:other_gun", Set.of("pack:gun_b"));
        Snapshot snapshot = new Snapshot(424, infos, support, Map.of("scope", AttachmentLootPolicy.APPROVED_SCOPES));
        List<Entry> entries = new ArrayList<>(List.of(entry("pack:special_stock", 1), entry("pack:common_stock", 1),
                entry("pack:no_support", 1), entry("pack:special_muzzle", 1), entry("pack:special_grip", .001),
                entry("cbrg:laser_sight", 1), entry("pack:disabled", 0),
                new Entry("attachment", "pack:zero_count", 0, 0, 1), entry("pack:other_gun", 1)));
        entries.add(entry("pack:special_stock", 1)); // candidate safety also handles a raw unvalidated duplicate fixture
        Map<String, Double> categoryProbabilities = new HashMap<>(LootCategory.defaultProbabilities());
        categoryProbabilities.put("gun", .25);
        categoryProbabilities.put("stock", .20);
        Document original = new Document(General.defaults(), entries, all, categoryProbabilities);
        Document effective = AttachmentLootPolicy.applyWeights(original, snapshot);
        check(effective.categoryProbabilities().equals(original.categoryProbabilities()), "Experimental filters preserve authored category probabilities");
        check(original.items().getFirst().weight() == 1, "Author base document remains unchanged");
        check(effective.items().getFirst().weight() == .01, "Effective document contains cap");
        check(AttachmentLootPolicy.applyWeights(original, snapshot).equals(effective), "Repeated generation has identical effective weights");
        var authoredSupply = new LootConfigModel.GunAmmoSupply(List.of(
                new LootConfigModel.AmmoOption("self", 1, .2),
                new LootConfigModel.AmmoOption("pack:other_ammo", 3, .7),
                new LootConfigModel.AmmoOption("self", 2, .1)), 17);
        Entry suppliedGun = new Entry("gun", "pack:gun_a", 1, 1, 1, authoredSupply);
        Document suppliedDocument = new Document(General.defaults(), List.of(suppliedGun), all);
        check(AttachmentLootPolicy.applyWeights(suppliedDocument, snapshot).items().getFirst().ammoSupply().equals(authoredSupply),
                "Experimental weight snapshots preserve all per-gun ammo IDs, chances and quantities");
        check(suppliedGun.withWeight(0).ammoSupply().equals(authoredSupply),
                "Changing gun loot weight cannot reset authored companion ammo");
        check(AttachmentLootPolicy.applyWeights(new Document(original.general(), original.items(), initial), snapshot).items().getFirst().weight() == 1,
                "Initial view restores base without mutating saved values");
        List<String> candidates = AttachmentLootPolicy.companionCandidates(original, snapshot, "pack:gun_a");
        check(new HashSet<>(candidates).equals(Set.of("pack:special_stock", "pack:special_muzzle", "pack:special_grip")) && candidates.size() == 3,
                "Companions only enabled positive-count positive-weight compatible rare stock/muzzle/grip, deduplicated");
        check(AttachmentLootPolicy.companionCandidates(new Document(original.general(), original.items(), initial), snapshot, "pack:gun_a").isEmpty(),
                "Initial categories have no specialist companions");
        check(AttachmentLootPolicy.companionCandidates(original, snapshot, "pack:unloaded_gun").isEmpty(), "No unrecorded gun candidate");
        System.out.println("AttachmentLootPolicyChecks passed: " + checks + " checks; effective pools, independent switches, 10% rarity, base-zero/count-zero, native-support metadata and companion isolation.");
    }
}
