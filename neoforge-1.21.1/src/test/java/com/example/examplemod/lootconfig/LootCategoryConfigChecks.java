package com.example.examplemod.lootconfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettingCategory;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import com.google.gson.JsonParser;

/** Configuration, semantic classification and migration only; no TaCZ inventory or game starts. */
public final class LootCategoryConfigChecks {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Runnable action, String label) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(label);
    }
    private static Document document(Map<String, Double> probabilities) {
        return new Document(General.defaults(), List.of(new Entry("gun", "tacz:m4a1", 1, 1, 3.5)),
                GunSettings.defaults(), probabilities);
    }

    public static void main(String[] args) {
        Set<String> ids = Set.of("gun", "ammo", "scope", "magazine", "muzzle", "grip", "stock", "laser", "other_attachment", "item",
                "helmet", "vest", "backpack", "throwable", "medical");
        Map<String, Double> legacyExpected = Map.of("gun", .4, "ammo", 0., "scope", .15, "magazine", .15,
                "muzzle", .1, "grip", .08, "stock", .05, "laser", .02, "other_attachment", 0., "item", .05);
        Map<String, Double> expected = PubgLootDefaults.profile(LootTier.HIGH).categoryProbabilities();
        check(LootCategory.values().length == 15 && LootCategory.IDS.equals(ids), "Fifteen stable category keys");
        check(LootCategory.defaultProbabilities().equals(expected), "Reviewed default pool probabilities");
        check(LootConfigModel.defaultCategoryProbabilities().equals(expected), "Model exposes matching default map");
        check(Math.abs(LootConfigModel.categoryProbabilityTotal(expected) - 1) <= 1e-12, "Defaults total one");
        for (LootCategory category : LootCategory.values()) {
            check(category.defaultProbability() == expected.get(category.id()), "Enum default " + category.id());
            check(LootCategory.fromId(category.id()) == category && !category.label().isBlank(), "Stable lookup and label " + category.id());
        }
        check(LootCategory.fromId("unknown") == null, "Unknown category lookup explicit");
        check(LootCategory.classify(new Entry("gun", "test:gun", 1, 1, 1), "laser") == LootCategory.GUN, "Gun kind wins over attachment metadata");
        check(LootCategory.classify(new Entry("ammo", "test:ammo", 1, 1, 1), "scope") == LootCategory.AMMO, "Ammo kind wins");
        check(LootCategory.classify(new Entry("item", "minecraft:apple", 1, 1, 1), "stock") == LootCategory.ITEM, "Ordinary item kind wins");
        Entry attachment = new Entry("attachment", "cbrg:laser_sight", 1, 1, 1);
        check(LootCategory.classify(attachment, "laser") == LootCategory.LASER, "Semantic LASER stays separate from physical GRIP");
        for (String semantic : List.of("scope", "magazine", "muzzle", "grip", "stock", "laser"))
            check(LootCategory.classify(attachment, semantic) == LootCategory.fromId(semantic), "Metadata classification " + semantic);
        check(LootCategory.classify(attachment, "extended_mag") == LootCategory.MAGAZINE, "Native magazine metadata alias");
        check(LootCategory.classify(attachment, null) == LootCategory.OTHER_ATTACHMENT
                && LootCategory.classify(attachment, "unknown") == LootCategory.OTHER_ATTACHMENT, "Unclassified attachments use dedicated pool");

        List<Entry> entries = List.of(new Entry("gun", "tacz:m4a1", 1, 1, 2.5),
                new Entry("attachment", "cbrg:red_dot", 1, 1, .001),
                new Entry("ammo", "cbrg:ammo_556", 30, 30, 0));
        General customGeneral = new General(40, .8, 0, .5, 2, .2, 1.2, 2.4, .37, 7);
        GunSettings customSettings = GunSettings.defaults().withValue(GunSettingCategory.MAGAZINE, LootConfigModel.EXPERIMENTAL)
                .withValue(GunSettingCategory.LASER, LootConfigModel.EXPERIMENTAL);
        Document oldTwo = new Document(customGeneral, entries);
        Document oldThree = new Document(customGeneral, entries, customSettings);
        check(oldTwo.categoryProbabilities().equals(expected) && oldThree.categoryProbabilities().equals(expected), "Old two/three constructors receive category defaults");
        check(new Document(customGeneral, entries, "researched").categoryProbabilities().equals(expected), "Legacy-profile constructor receives defaults");
        check(LootConfigModel.parse(LootConfigModel.toJson(oldThree)).equals(oldThree), "New category JSON round-trip with a/b/settings/base weights");
        var oldJson = JsonParser.parseString(LootConfigModel.toJson(oldThree)).getAsJsonObject();
        oldJson.remove("categoryProbabilities");
        Document migrated = LootConfigModel.parse(oldJson.toString());
        check(migrated.categoryProbabilities().equals(expected), "Old item JSON migrates to defaults");
        check(migrated.items().equals(entries) && migrated.general().equals(customGeneral) && migrated.gunSettings().equals(customSettings),
                "Migration preserves every base item weight, General a/b and independent choice");
        var custom = new LinkedHashMap<>(expected); custom.put("gun", .11); custom.put("item", .15);
        Document configured = new Document(customGeneral, entries, customSettings, custom);
        check(LootConfigModel.parse(LootConfigModel.toJson(configured)).equals(configured), "Custom normalized probabilities round-trip");
        custom.put("gun", .50);
        check(configured.categoryProbabilities().get("gun") == .11, "Document defensively copies probability map");
        try {
            configured.categoryProbabilities().put("gun", .5); throw new AssertionError("Probability map must be immutable");
        } catch (UnsupportedOperationException expectedImmutable) { checks++; }

        var draftMap = new HashMap<>(expected); draftMap.put("gun", .30);
        Document draft = document(draftMap);
        List<String> errors = LootConfigModel.validate(draft);
        check(errors.size() == 1 && LootConfigModel.isCategoryTotalError(errors.getFirst()), "Draft constructor permits temporary total; validation identifies only total error");
        check(errors.getFirst().startsWith(LootConfigModel.CATEGORY_TOTAL_ERROR_PREFIX), "Public stable total-error marker");
        check(!LootConfigModel.isCategoryTotalError("其他范围错误") && !LootConfigModel.isCategoryTotalError(null), "Do not relax unrelated errors");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(draft)), "Save parse refuses unfinished total");
        var tolerated = new HashMap<>(expected); tolerated.put("gun", .26 + 5e-10);
        check(LootConfigModel.validate(document(tolerated)).isEmpty(), "Document total tolerance accepts floating-point noise");
        var tooFar = new HashMap<>(expected); tooFar.put("gun", .26 + 2e-9);
        check(LootConfigModel.validate(document(tooFar)).stream().anyMatch(LootConfigModel::isCategoryTotalError), "Total tolerance rejects material deviation");
        for (double invalid : new double[]{-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var bad = new HashMap<>(expected); bad.put("gun", invalid);
            check(LootConfigModel.validate(document(bad)).stream().anyMatch(error -> !LootConfigModel.isCategoryTotalError(error)), "Invalid per-category number is not bypassable as a total error");
        }
        var missing = new HashMap<>(expected); missing.remove("ammo");
        check(!LootConfigModel.validate(document(missing)).isEmpty(), "Map must retain all ten categories even when probability zero");
        var unknown = new HashMap<>(expected); unknown.put("new_pool", 0.);
        check(!LootConfigModel.validate(document(unknown)).isEmpty(), "Unknown map key rejected");
        var nullValue = new HashMap<>(expected); nullValue.put("scope", null);
        check(!LootConfigModel.validate(document(nullValue)).isEmpty(), "Null map probability rejected");

        var malformed = JsonParser.parseString(LootConfigModel.toJson(oldThree)).getAsJsonObject();
        malformed.getAsJsonObject("categoryProbabilities").addProperty("mystery", 0);
        rejects(() -> LootConfigModel.parse(malformed.toString()), "Unknown category JSON key rejected");
        malformed.getAsJsonObject("categoryProbabilities").remove("mystery");
        malformed.getAsJsonObject("categoryProbabilities").remove("ammo");
        rejects(() -> LootConfigModel.parse(malformed.toString()), "Missing category JSON key rejected");
        malformed.getAsJsonObject("categoryProbabilities").addProperty("ammo", "0");
        rejects(() -> LootConfigModel.parse(malformed.toString()), "Numeric string category probability rejected");
        String json = LootConfigModel.toJson(oldThree);
        String duplicate = json.replace("\"categoryProbabilities\": {", "\"categoryProbabilities\": {\"gun\": 0.4,");
        rejects(() -> LootConfigModel.parse(duplicate), "Duplicate nested category key rejected before Gson can overwrite");
        var nullMap = JsonParser.parseString(json).getAsJsonObject();
        nullMap.add("categoryProbabilities", com.google.gson.JsonNull.INSTANCE);
        rejects(() -> LootConfigModel.parse(nullMap.toString()), "Null probability map rejected");

        String legacyGroups = """
                {"general":{"maxGroupsPerPlane":128,"planeSpawnChance":1,"minGroupsPerPlane":0,
                "samePlaneYTolerance":0.5,"minGroupSpacing":2,"lootDensity":0.1},"groups":[
                {"id":"old","name":"旧物资组","enabled":true,"weight":3,"entries":[
                {"kind":"gun","id":"tacz:m4a1","minCount":1,"maxCount":1,"chance":1},
                {"kind":"ammo","id":"tacz:556x45","minCount":30,"maxCount":30,"chance":1}]}]}
                """;
        Document groupsMigrated = LootConfigModel.parse(legacyGroups);
        check(groupsMigrated.categoryProbabilities().equals(expected) && groupsMigrated.items().getFirst().weight() == 3
                && groupsMigrated.items().getLast().weight() == 0, "Old groups receive category defaults and retain existing item migration");
        Document tiers = new Document(General.defaults(), entries);
        check(tiers.tierProfiles().size() == 5, "old constructor independently inherits all five tiers");
        var lowCategories = new LinkedHashMap<>(expected);
        lowCategories.put("gun", .11); lowCategories.put("item", .15);
        var lowItems = List.of(new Entry("item", "minecraft:diamond", 1, 3, 9.5));
        tiers = tiers.withTier(LootTier.LOW, new LootConfigModel.TierProfile(lowItems, lowCategories));
        check(tiers.forTier(LootTier.LOW).items().equals(lowItems), "selected tier supplies its complete independent item pool");
        check(tiers.forTier(LootTier.HIGH).items().equals(entries), "other tier keeps inherited original pool");
        check(tiers.forTier(LootTier.LOW).categoryProbabilities().get("gun") == .11, "tier category percentages are independent");
        check(LootConfigModel.parse(LootConfigModel.toJson(tiers)).equals(tiers), "five full item/probability profiles survive strict JSON");
        var badTiers = JsonParser.parseString(LootConfigModel.toJson(tiers)).getAsJsonObject();
        badTiers.getAsJsonObject("tierProfiles").remove("very_low");
        rejects(() -> LootConfigModel.parse(badTiers.toString()), "partial tier profiles cannot silently reset another tier");
        var legacyMap = new com.google.gson.JsonObject();
        legacyExpected.forEach(legacyMap::addProperty);
        var legacyDocument = JsonParser.parseString(LootConfigModel.toJson(oldThree)).getAsJsonObject();
        legacyDocument.remove("tierProfiles");
        legacyDocument.add("categoryProbabilities", legacyMap);
        check(LootConfigModel.parse(legacyDocument.toString()).categoryProbabilities().equals(expected),
                "Ten-pool untouched defaults migrate to balanced equipment/consumable pools");
        legacyMap.addProperty("gun", .30); legacyMap.addProperty("item", .15);
        var customLegacy = LootConfigModel.parse(legacyDocument.toString());
        check(customLegacy.categoryProbabilities().get("gun") == .30
                && Math.abs(customLegacy.categoryProbabilities().get("medical") - .06) < 1e-12,
                "Customized legacy gun chance stays unchanged; empty old item pool divides its own mass");
        var withItems = new com.google.gson.JsonArray();
        for (var entry : List.of(new Entry("item", "minecraft:diamond", 1, 1, 3),
                new Entry("item", "tacz_pubg:bandage", 5, 5, 1)))
            withItems.add(com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(entry)));
        legacyDocument.add("items", withItems);
        var migratedItems = LootConfigModel.parse(legacyDocument.toString());
        check(Math.abs(migratedItems.categoryProbabilities().get("item") - .1125) < 1e-12
                && Math.abs(migratedItems.categoryProbabilities().get("medical") - .0375) < 1e-12,
                "Custom old item weights retain their exact effective probabilities after splitting");
        legacyMap.addProperty("item", -.15);
        rejects(() -> LootConfigModel.parse(legacyDocument.toString()), "Negative old item chance cannot disappear in migration");
        legacyFiveTierMigrations(legacyExpected);

        for (LootTier tier : LootTier.values()) {
            var profile = PubgLootDefaults.profile(tier);
            check(profile.items().size() == 22 && profile.items().stream().map(Entry::id).distinct().count() == 22,
                    tier + " includes all twenty-two usable mod items");
            check(Math.abs(LootConfigModel.categoryProbabilityTotal(profile.categoryProbabilities()) - 1) < 1e-12,
                    tier + " category total");
            for (var category : List.of(LootCategory.HELMET, LootCategory.VEST, LootCategory.BACKPACK,
                    LootCategory.THROWABLE, LootCategory.MEDICAL))
                check(profile.items().stream().filter(e -> LootCategory.classify(e, "") == category)
                        .mapToDouble(Entry::weight).sum() == 100, tier + " conditional weight sum " + category);
            check(profile.items().stream().noneMatch(e -> LootCategory.classify(e, "") == LootCategory.ITEM),
                    tier + " every own item goes to its dedicated category");
        }
        check(LootCategory.itemCategory("other:helmet_1") == LootCategory.ITEM
                && LootCategory.itemCategory("tacz_pubg:helmet_future") == LootCategory.ITEM,
                "Third-party and unknown IDs cannot silently enter restricted pools");
        check(PubgLootDefaults.profile(LootTier.LOW).items().stream()
                .filter(e -> e.id().equals("tacz_pubg:helmet_3")).findFirst().orElseThrow().weight() == 0,
                "Low resource areas keep tier-three helmets disabled");
        System.out.println("LootCategoryConfigChecks passed: " + checks + " checks; fifteen pools, independent profiles, migration and balanced item defaults.");
    }

    private static void legacyFiveTierMigrations(Map<String, Double> legacyCategories) {
        var originalItems = List.of(new Entry("gun", "tacz:m4a1", 1, 1, 7.25),
                new Entry("attachment", "cbrg:red_dot", 1, 2, .125),
                new Entry("item", "tacz_pubg:helmet_3", 1, 1, 0));
        var old = JsonParser.parseString(LootConfigModel.toJson(new Document(General.defaults(), originalItems,
                GunSettings.defaults(), legacyCategories))).getAsJsonObject();
        var onePool = old.deepCopy();
        onePool.remove("tierProfiles");
        var inherited = LootConfigModel.parse(onePool.toString());
        var full = LootConfigModel.parse(old.toString());
        for (LootTier tier : LootTier.values()) {
            check(inherited.tier(tier).categoryProbabilities().equals(PubgLootDefaults.profile(tier).categoryProbabilities()),
                    tier + " pre-tier untouched defaults acquire this tier's own category probabilities");
            check(full.tier(tier).categoryProbabilities().equals(PubgLootDefaults.profile(tier).categoryProbabilities()),
                    tier + " five-profile untouched defaults acquire this tier's own category probabilities");
            check(inherited.tier(tier).items().equals(originalItems) && full.tier(tier).items().equals(originalItems),
                    tier + " migration preserves non-default gun/attachment weights and explicitly disabled equipment");
        }
        check(LootConfigModel.parse(LootConfigModel.toJson(inherited)).equals(inherited)
                        && LootConfigModel.parse(LootConfigModel.toJson(full)).equals(full),
                "Both legacy default shapes become stable modern documents on a second parse");

        // Old default categories do not imply an untouched configuration: custom generic items must still spawn.
        var weightedItems = List.of(new Entry("item", "minecraft:diamond", 2, 3, 3),
                new Entry("item", "tacz_pubg:bandage", 5, 5, 1),
                new Entry("item", "tacz_pubg:helmet_3", 1, 1, 0));
        var authored = JsonParser.parseString(LootConfigModel.toJson(new Document(General.defaults(), weightedItems,
                GunSettings.defaults(), legacyCategories))).getAsJsonObject();
        var authoredSingle = authored.deepCopy();
        authoredSingle.remove("tierProfiles");
        for (var fixture : List.of(authored, authoredSingle)) {
            var migrated = LootConfigModel.parse(fixture.toString());
            check(migrated.items().equals(weightedItems), "Custom items with old default categories preserve weights/counts");
            for (LootTier tier : LootTier.values()) {
                var profile = migrated.tier(tier);
                check(profile.items().equals(weightedItems), tier + " retains exact custom item entries");
                check(profile.categoryProbabilities().get("gun") == .4,
                        tier + " custom ordinary-item pool does not trigger replacement of unrelated category chances");
                check(Math.abs(profile.categoryProbabilities().get("item") - .0375) < 1e-12
                                && Math.abs(profile.categoryProbabilities().get("medical") - .0125) < 1e-12,
                        tier + " keeps old diamond and bandage effective chances after splitting the old five-percent pool");
                check(profile.categoryProbabilities().get("helmet") == 0,
                        tier + " explicitly disabled equipment does not acquire category mass");
            }
            check(LootConfigModel.parse(LootConfigModel.toJson(migrated)).equals(migrated),
                    "Custom old-default category migration is idempotent");
        }

        // Different authorship in each old tier must not be replaced by the root or another tier's choices.
        var distinct = old.deepCopy();
        Map<String, List<Entry>> tierItems = new LinkedHashMap<>();
        int index = 0;
        for (LootTier tier : LootTier.values()) {
            double itemChance = .10 + .025 * index;
            var categories = new LinkedHashMap<>(legacyCategories);
            categories.put("item", itemChance);
            categories.put("gun", .45 - itemChance);
            var items = List.of(new Entry("gun", "tacz:m4a1", 1, 1, 2 + index),
                    new Entry("item", "minecraft:diamond", 1 + index, 2 + index, 3),
                    new Entry("item", "tacz_pubg:bandage", 5, 5, 1),
                    new Entry("item", "tacz_pubg:helmet_3", 1, 1, 0));
            tierItems.put(tier.id(), items);
            var profile = distinct.getAsJsonObject("tierProfiles").getAsJsonObject(tier.id());
            profile.add("items", new com.google.gson.Gson().toJsonTree(items));
            profile.add("categoryProbabilities", new com.google.gson.Gson().toJsonTree(categories));
            index++;
        }
        var migrated = LootConfigModel.parse(distinct.toString());
        index = 0;
        for (LootTier tier : LootTier.values()) {
            var profile = migrated.tier(tier);
            double itemChance = .10 + .025 * index;
            check(profile.items().equals(tierItems.get(tier.id())), tier + " retains its own custom counts and weights");
            check(Math.abs(profile.categoryProbabilities().get("gun") - (.45 - itemChance)) < 1e-12
                            && Math.abs(profile.categoryProbabilities().get("item") - itemChance * .75) < 1e-12
                            && Math.abs(profile.categoryProbabilities().get("medical") - itemChance * .25) < 1e-12,
                    tier + " splits only its own authored item mass");
            check(Math.abs(LootConfigModel.categoryProbabilityTotal(profile.categoryProbabilities()) - 1) < 1e-12,
                    tier + " custom migration retains a normalized distribution");
            check(migrated.forTier(tier).general().equals(migrated.general()),
                    tier + " probability selection leaves independent quantity/general settings untouched");
            index++;
        }
        check(LootConfigModel.parse(LootConfigModel.toJson(migrated)).equals(migrated),
                "Five different custom legacy profiles survive a modern JSON round trip");
        check(migrated.items().equals(originalItems), "Tier migration never rewrites root pool entries");
    }
}
