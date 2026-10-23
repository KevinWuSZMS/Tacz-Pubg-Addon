package com.example.examplemod.lootconfig;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettingCategory;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import com.example.examplemod.lootconfig.LootSpawnRules.FloorSample;
import net.minecraft.core.BlockPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Random;

/** Pure serialization, migration, weighting, density and transfer policy; no game is launched. */
public final class LootConfigChecks {
    private static int checks;
    public static void main(String[] args) {
        Entry gun = new Entry("gun", "tacz:ak47", 1, 1, 20);
        Entry ammo = new Entry("ammo", "tacz:762x39", 30, 30, 0);
        Document document = new Document(General.defaults(), List.of(gun, ammo));
        check(LootConfigModel.parse(LootConfigModel.toJson(document)).equals(document), "new item document round-trip");
        operatorPermissions(document);
        check(!LootConfigModel.toJson(document).contains("groups"), "new JSON has no groups");
        check(document.gunSettings().equals(GunSettings.defaults()), "old constructor starts every gun category at initial");
        check(document.general().companionAttachmentChance() == 0.5 && document.general().companionAttachmentKinds() == 2,
                "existing constructors preserve safe companion attachment defaults");
        var previousGeneral = com.google.gson.JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        previousGeneral.getAsJsonObject("general").remove("companionAttachmentChance");
        previousGeneral.getAsJsonObject("general").remove("companionAttachmentKinds");
        check(LootConfigModel.parse(previousGeneral.toString()).equals(document), "old general JSON receives a/b defaults without altering weights");
        var companionGeneral = new General(20, 1, 0, 0.5, 2, 0.1, 1, 1.5, 0.75, 5);
        var companionDocument = new Document(companionGeneral, document.items(), document.gunSettings());
        check(LootConfigModel.parse(LootConfigModel.toJson(companionDocument)).equals(companionDocument), "custom a/b round trip");
        check(LootConfigModel.validate(new Document(new General(20, 1, 0, 0.5, 2, 0.1, 1, 1.5, 0, 0), document.items())).isEmpty(),
                "zero probability and zero kinds disable optional companions");
        for (double invalid : new double[]{-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
            check(!LootConfigModel.validate(new Document(new General(20, 1, 0, 0.5, 2, 0.1, 1, 1.5, invalid, 2), document.items())).isEmpty(),
                    "invalid companion probability rejected");
        for (int invalid : new int[]{-1, 17})
            check(!LootConfigModel.validate(new Document(new General(20, 1, 0, 0.5, 2, 0.1, 1, 1.5, 0.5, invalid), document.items())).isEmpty(),
                    "invalid companion kind count rejected");
        Document researched = new Document(document.general(), document.items(), "researched");
        check(LootConfigModel.parse(LootConfigModel.toJson(researched)).equals(researched), "independent gun settings round-trip");
        check(!LootConfigModel.toJson(researched).contains("gunBalanceProfile"), "new document only serializes independent settings");
        var legacy = com.google.gson.JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        legacy.remove("gunSettings");
        check(LootConfigModel.parse(legacy.toString()).gunSettings().equals(GunSettings.defaults()),
                "old item configuration without gun profile migrates to all initial choices");
        legacy.addProperty("gunBalanceProfile", "researched");
        GunSettings migratedSettings = LootConfigModel.parse(legacy.toString()).gunSettings();
        check(migratedSettings.balance().equals("experimental") && migratedSettings.scopes().equals("experimental")
                        && migratedSettings.ammo().equals("initial") && migratedSettings.muzzle().equals("initial"),
                "legacy researched preserves balance and scopes without enabling future categories");
        legacy.addProperty("gunBalanceProfile", "unknown");
        rejects(() -> LootConfigModel.parse(legacy.toString()), "unknown legacy profile rejected");
        legacy.addProperty("gunBalanceProfile", "default");
        check(LootConfigModel.parse(legacy.toString()).gunSettings().equals(GunSettings.defaults()),
                "legacy default migrates to all initial choices");
        for (GunSettingCategory category : GunSettingCategory.values()) {
            GunSettings independent = GunSettings.defaults().withValue(category, LootConfigModel.EXPERIMENTAL);
            Document selected = new Document(document.general(), document.items(), independent);
            check(LootConfigModel.parse(LootConfigModel.toJson(selected)).equals(selected), category.id() + " independently persists");
            for (GunSettingCategory other : GunSettingCategory.values())
                check(independent.isExperimental(other) == (other == category), category.id() + " does not affect " + other.id());
            rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(new Document(document.general(), document.items(),
                    independent.withValue(category, "unknown")))), category.id() + " rejects unknown choice");
        }
        var conflicting = com.google.gson.JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        conflicting.addProperty("gunBalanceProfile", "researched");
        rejects(() -> LootConfigModel.parse(conflicting.toString()), "new and legacy gun settings cannot conflict");
        var missingChoice = com.google.gson.JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        missingChoice.getAsJsonObject("gunSettings").remove("scopes");
        rejects(() -> LootConfigModel.parse(missingChoice.toString()), "new settings cannot silently omit a category");
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 5000; i++) many.add(new Entry("item", "test:item_" + i, 1, 1, 0));
        Document manyDocument = new Document(General.defaults(), many);
        check(LootConfigModel.parse(LootConfigModel.toJson(manyDocument)).items().size() == 5000,
                "more than 128 and 4096 distinct items accepted without array truncation");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(new Document(General.defaults(), List.of(gun, gun)))),
                "duplicate kind/ID rejected");
        check(LootConfigModel.validate(new Document(General.defaults(), List.of(
                new Entry("gun", "test:same", 1, 1, 1), new Entry("ammo", "test:same", 1, 1, 0)))).isEmpty(),
                "asset kinds retain separate identities");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(document).replace("20.0", "-1")), "negative weight rejected");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(document).replace("20.0", "1e309")), "infinite weight rejected");
        rejects(() -> LootConfigModel.parse("{\"general\":{}," + LootConfigModel.toJson(document).substring(1)),
                "duplicate root key rejected");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(document) + " true"), "trailing JSON rejected");
        String old = """
                {"general":{"maxGroupsPerPlane":128,"planeSpawnChance":1,"minGroupsPerPlane":0,
                "samePlaneYTolerance":0.5,"minGroupSpacing":2,"lootDensity":0.1},"groups":[
                {"id":"one","name":"旧枪组","enabled":true,"weight":2,"entries":[
                {"kind":"gun","id":"tacz:ak47","minCount":1,"maxCount":1,"chance":1},
                {"kind":"ammo","id":"tacz:762x39","minCount":30,"maxCount":30,"chance":1,"ammoForGun":"tacz:ak47"}]},
                {"id":"two","name":"重复项","enabled":true,"weight":3,"entries":[
                {"kind":"gun","id":"tacz:ak47","minCount":1,"maxCount":1,"chance":1}]}]}
                """;
        Document migrated = LootConfigModel.parse(old);
        check(migrated.items().size() == 2, "legacy duplicate gun entries merge once");
        check(migrated.items().getFirst().weight() == 5, "legacy repeated item weights summed");
        check(migrated.items().getLast().weight() == 0, "legacy ammo initially disabled");
        String largeWeights = old.replace("\"weight\":2", "\"weight\":1000000")
                .replace("\"weight\":3", "\"weight\":1000000")
                .replace("\"groups\":[", "\"groups\":[{\"id\":\"three\",\"name\":\"第三项\",\"enabled\":true,\"weight\":1000000,"
                        + "\"entries\":[{\"kind\":\"attachment\",\"id\":\"tacz:sight\",\"minCount\":1,\"maxCount\":1,\"chance\":1}]},");
        Document normalized = LootConfigModel.parse(largeWeights);
        check(normalized.items().get(1).weight() / normalized.items().getFirst().weight() == 2,
                "large duplicate legacy weights keep two-to-one relative probability through normalization");
        check(migrated.general().maxItemsPerPlane() == Integer.MAX_VALUE, "obsolete 128 spawn cap removed on migration");
        check(migrated.general().ammoRadiusMin() == 1 && migrated.general().ammoRadiusMax() == 1.5,
                "migration gets configured ammo annulus defaults");
        Entry other = new Entry("attachment", "tacz:sight", 1, 1, 10);
        var categoryProbabilities = new LinkedHashMap<String, Double>();
        for (LootCategory category : LootCategory.values()) categoryProbabilities.put(category.id(), 0D);
        categoryProbabilities.put(LootCategory.GUN.id(), 2D / 3);
        categoryProbabilities.put(LootCategory.OTHER_ATTACHMENT.id(), 1D / 3);
        Document weighted = new Document(General.defaults(), List.of(gun, ammo, other),
                GunSettings.defaults(), categoryProbabilities);
        Random random = new Random(717);
        int gunCount = 0;
        for (int i = 0; i < 30000; i++) {
            Entry selected = LootSpawnRules.chooseItem(weighted, random).orElseThrow();
            if (selected == gun) gunCount++;
            if ("ammo".equals(selected.kind())) throw new AssertionError("disabled ammo independently selected");
        }
        check(gunCount > 19500 && gunCount < 20500, "configured category shares give approximately two-to-one selection");
        check(LootSpawnRules.chooseItem(new Document(General.defaults(), List.of(ammo)), random).isEmpty(),
                "all-zero catalog selects no item");
        List<FloorSample> floors = new ArrayList<>();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) floors.add(new FloorSample(new BlockPos(x, 64, z), 65));
        General settings = new General(Integer.MAX_VALUE, 1, 0, .5, 0, .1, 1, 1.5);
        var plan = LootSpawnRules.plan(floors, new Document(settings, List.of(gun, ammo)), 1, new Random(77));
        check(plan.size() == 10 && plan.stream().allMatch(item -> item.entry().equals(gun) && item.count() == 1),
                "100 square metres produces ten independent items, no ammo in initial pass");
        check(LootSpawnRules.plan(floors, new Document(settings, List.of(gun)), 0, random).isEmpty(),
                "zero region rarity disables generation");
        int two = 0, three = 0;
        for (int i = 0; i < 10000; i++) {
            int count = LootSpawnRules.rollDensityCount(25, 1, .1, random);
            if (count == 2) two++; else if (count == 3) three++; else throw new AssertionError("invalid stochastic rounding");
        }
        check(two > 4700 && three > 4700, "25 square metres uses two plus half probability");
        var layers = LootSpawnRules.planes(List.of(new FloorSample(new BlockPos(0, 64, 0), 65),
                new FloorSample(new BlockPos(1, 64, 0), 65.5), new FloorSample(new BlockPos(2, 65, 0), 66)), .5);
        check(layers.size() == 2, "whole-plane tolerance prevents stair chaining");
        String json = LootConfigModel.toJson(manyDocument);
        check(String.join("", LootConfigPayloads.split(json)).equals(json), "large draft transfers losslessly");
        for (String part : LootConfigPayloads.split(json)) check(part.length() <= LootConfigPayloads.CHUNK_CHARACTERS,
                "individual draft packet bounded");
        check(json.getBytes(StandardCharsets.UTF_8).length < LootConfigModel.MAX_JSON_BYTES, "large catalog within byte budget");
        check(!LootConfigModel.validate(new Document(new General(1, 1, 0, .5, 1, .1, 2, 1), List.of(gun))).isEmpty(),
                "inverted ammo annulus rejected");
        System.out.println("Loot configuration checks passed: " + checks);
    }
    private static void operatorPermissions(Document document) {
        var defaults = com.example.examplemod.operatorpermissions.OperatorPermissions.defaults();
        check(defaults.damageBots() && defaults.bypassPreparationProtection() && !defaults.damagePlayers()
                && !defaults.bypassFriendlyFire() && !defaults.immuneBlueZone() && !defaults.keepGameMode(),
                "OP defaults enable managed-bot intervention without changing player isolation, friendly fire, blue damage or match modes");
        var legacy = com.google.gson.JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        legacy.getAsJsonObject("general").remove("operatorPermissions");
        check(LootConfigModel.parse(legacy.toString()).general().operatorPermissions().equals(defaults),
                "old configuration automatically receives OP defaults");
        for (var permission : com.example.examplemod.operatorpermissions.OperatorPermissions.Permission.values()) {
            var changed = defaults.with(permission, !defaults.value(permission));
            Document candidate = new Document(document.general().withOperatorPermissions(changed), document.items(),
                    document.gunSettings(), document.categoryProbabilities(), document.tierProfiles());
            check(LootConfigModel.parse(LootConfigModel.toJson(candidate)).equals(candidate), permission.field() + " round trips with all tier profiles intact");
            for (var other : com.example.examplemod.operatorpermissions.OperatorPermissions.Permission.values())
                if (other != permission) check(changed.value(other) == defaults.value(other), permission.field() + " does not alter " + other.field());
        }
        var invalid = com.google.gson.JsonParser.parseString(LootConfigModel.toJson(document)).getAsJsonObject();
        invalid.getAsJsonObject("general").getAsJsonObject("operatorPermissions").addProperty("damageBots", "true");
        rejects(() -> LootConfigModel.parse(invalid.toString()), "OP privileges reject string values instead of silently enabling them");
        invalid.getAsJsonObject("general").getAsJsonObject("operatorPermissions").remove("damageBots");
        rejects(() -> LootConfigModel.parse(invalid.toString()), "an explicit OP permission object must provide every capability");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
}
