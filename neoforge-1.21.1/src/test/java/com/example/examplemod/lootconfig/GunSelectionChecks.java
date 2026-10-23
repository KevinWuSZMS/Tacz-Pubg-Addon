package com.example.examplemod.lootconfig;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/** Gun-family probabilities, count defaults, item weights and legacy persistence without a game launch. */
public final class GunSelectionChecks {
    private static int checks;
    private static final Entry RIFLE_A = gun("rifle_a", 1);
    private static final Entry RIFLE_B = gun("rifle_b", 3);
    private static final Entry SMG = gun("smg_a", 2);
    private static final Entry HEAVY = gun("heavy_a", 1);
    private static final Entry ITEM = new Entry("item", "minecraft:apple", 1, 1, 1);
    private static final List<Entry> ENTRIES = List.of(RIFLE_A, RIFLE_B, SMG, HEAVY, ITEM);
    private static final Function<Entry, LootCategory> CATEGORY = entry -> LootCategory.classify(entry, "");
    private static final Function<Entry, GunClass> CLASS = entry -> {
        String path = entry.id().substring(entry.id().indexOf(':') + 1);
        if (path.startsWith("rifle")) return GunClass.RIFLE;
        if (path.startsWith("smg")) return GunClass.SMG;
        if (path.startsWith("heavy")) return GunClass.HEAVY;
        return GunClass.OTHER;
    };
    private GunSelectionChecks() {}

    public static void main(String[] args) {
        classification(); automatic(); manual(); disabled(); validation(); persistence(); seededDraws();
        System.out.println("GunSelectionChecks: " + checks + " gun-family sampling and persistence assertions passed.");
    }

    private static void classification() {
        check(GunClass.values().length == 9 && GunClass.IDS.size() == 9, "Nine stable gun families");
        for (GunClass type : GunClass.values()) check(GunClass.byId(type.id()) == type && !type.label().isBlank(), "Stable family identity " + type.id());
        check(GunClass.classify("rifle", false) == GunClass.RIFLE && GunClass.classify("SMG", false) == GunClass.SMG,
                "Native rifle and SMG aliases");
        check(GunClass.classify("lmg", false) == GunClass.MG && GunClass.classify("sg", false) == GunClass.SHOTGUN,
                "Native machine-gun and shotgun aliases");
        check(GunClass.classify("sniper", false) == GunClass.SNIPER && GunClass.classify("rifle", true) == GunClass.DMR,
                "Reviewed DMR classification precedes the broad native rifle type");
        check(GunClass.classify("handgun", false) == GunClass.PISTOL && GunClass.classify("rpg", false) == GunClass.HEAVY,
                "Pistol and launcher classes");
        check(GunClass.classify("grenade_launcher", false) == GunClass.HEAVY && GunClass.classify(null, false) == GunClass.OTHER,
                "Grenade launcher and unknown fallback");
        rejects(() -> GunClass.byId("unknown"), "Unknown family ID is rejected");
    }

    private static void automatic() {
        check(GunSelection.defaults().isAutomatic() && GunSelection.defaults().validate().isEmpty(), "Dynamic count defaults are valid");
        TwoStageLootSampler sampler = sample(ENTRIES, GunSelection.defaults());
        near(sampler.gunClassProbability(GunClass.RIFLE), .5, "Two rifle IDs out of four default to half");
        near(sampler.gunClassProbability(GunClass.SMG), .25, "One SMG ID out of four");
        near(sampler.gunClassProbability(GunClass.HEAVY), .25, "One heavy ID out of four");
        near(sampler.withinGunClassProbability(RIFLE_A), .25, "Relative weights normalize only inside the rifle class");
        near(sampler.withinGunClassProbability(RIFLE_B), .75, "Second rifle keeps the larger within-family weight");
        near(sampler.withinPoolProbability(RIFLE_A), .125, "Gun-pool share multiplies family and item probabilities");
        near(sampler.itemProbability(RIFLE_A), .1, "Overall share also includes the top-level gun category");
        near(sampler.itemProbability(ITEM), .2, "Ordinary items keep their top-level category share");
        check(sampler.gunClassCount(GunClass.RIFLE) == 2 && sampler.gunClassCount(GunClass.SMG) == 1,
                "Selectable item counts are exposed separately from catalog defaults");

        List<Entry> changed = List.of(RIFLE_A, RIFLE_B.withWeight(999_999), SMG, HEAVY, ITEM);
        TwoStageLootSampler weighted = sample(changed, GunSelection.defaults());
        near(weighted.gunClassProbability(GunClass.RIFLE), .5, "Large item weights do not alter automatic class counts");
        near(weighted.withinGunClassProbability(RIFLE_A), 1D / 1_000_000, "Large weights affect the local weighted draw");
        List<Entry> withDisabled = List.of(RIFLE_A, RIFLE_B.withWeight(0), SMG, HEAVY, ITEM);
        TwoStageLootSampler disabled = sample(withDisabled, GunSelection.defaults());
        near(disabled.gunClassProbability(GunClass.RIFLE), .5, "Three-argument defaults count disabled IDs as catalog members");
        near(disabled.withinGunClassProbability(RIFLE_A), 1, "Disabled items leave their class's active pool");
        check(disabled.gunClassCount(GunClass.RIFLE) == 1 && disabled.itemProbability(RIFLE_B) == 0,
                "Disabled rifle remains individually closed");

        Map<GunClass, Integer> catalog = counts(GunClass.RIFLE, 10, GunClass.SMG, 30, GunClass.HEAVY, 60);
        TwoStageLootSampler fullCatalog = TwoStageLootSampler.prepare(doc(withDisabled, GunSelection.defaults()), CATEGORY, CLASS, catalog);
        near(fullCatalog.gunClassProbability(GunClass.RIFLE), .1, "Four-argument defaults use the full loaded rifle catalog");
        near(fullCatalog.gunClassProbability(GunClass.SMG), .3, "Full catalog counts are independent of document subset size");
        near(fullCatalog.gunClassProbability(GunClass.HEAVY), .6, "Largest catalog class receives the largest automatic share");
        catalog.put(GunClass.RIFLE, 100_000);
        near(fullCatalog.gunClassProbability(GunClass.RIFLE), .1, "Prepared selection captures a catalog snapshot");
        total(sampler, ENTRIES, "Automatic overall probabilities total one");
        total(fullCatalog, withDisabled, "Full-catalog active item probabilities total one");
    }

    private static void manual() {
        GunSelection selected = selection(GunClass.RIFLE, .6, GunClass.SMG, .3, GunClass.HEAVY, .1);
        check(selected.validate().isEmpty() && !selected.isAutomatic(), "Explicit normalized class probabilities");
        TwoStageLootSampler sampler = sample(ENTRIES, selected);
        near(sampler.gunClassProbability(GunClass.RIFLE), .6, "Manual rifle share");
        near(sampler.gunClassProbability(GunClass.SMG), .3, "Manual SMG share");
        near(sampler.itemProbability(RIFLE_A), .8 * .6 * .25, "Three probability stages multiply");
        List<Entry> expanded = new ArrayList<>(ENTRIES);
        for (int index = 0; index < 2_000; index++) expanded.add(gun("rifle_added_" + index, index % 5 + 1));
        TwoStageLootSampler manyRifles = sample(expanded, selected);
        for (GunClass type : GunClass.values()) near(manyRifles.gunClassProbability(type), sampler.gunClassProbability(type),
                "Adding many rifles preserves explicit family share " + type.id());
        near(manyRifles.poolProbability(LootCategory.GUN), .8, "Family size preserves the top-level gun share");
        near(manyRifles.itemProbability(SMG), sampler.itemProbability(SMG), "Other gun families keep their item probability");
        near(manyRifles.itemProbability(ITEM), .2, "Gun catalog size preserves ordinary-item share");
        check(manyRifles.withinGunClassProbability(RIFLE_A) < sampler.withinGunClassProbability(RIFLE_A),
                "Extra rifles dilute only the rifle-class item draw");
        TwoStageLootSampler countsIgnored = TwoStageLootSampler.prepare(doc(ENTRIES, selected), CATEGORY, CLASS,
                counts(GunClass.RIFLE, 100_000, GunClass.SMG, 1, GunClass.HEAVY, 1));
        near(countsIgnored.gunClassProbability(GunClass.SMG), .3, "Manual selection supersedes catalog count defaults");
        total(manyRifles, expanded, "Expanded manual item probabilities total one");
    }

    private static void disabled() {
        List<Entry> noRifles = List.of(RIFLE_A.withWeight(0), RIFLE_B.withWeight(0), SMG, HEAVY, ITEM);
        TwoStageLootSampler automatic = sample(noRifles, GunSelection.defaults());
        near(automatic.gunClassProbability(GunClass.RIFLE), 0, "Empty rifle family has zero actual share");
        near(automatic.gunClassProbability(GunClass.SMG), .5, "Empty automatic families reallocate proportionally");
        near(automatic.gunClassProbability(GunClass.HEAVY), .5, "Remaining automatic class ratio is retained");
        GunSelection manual = selection(GunClass.RIFLE, .6, GunClass.SMG, .3, GunClass.HEAVY, .1);
        TwoStageLootSampler reallocated = sample(noRifles, manual);
        near(reallocated.gunClassProbability(GunClass.SMG), .75, "Manual empty-family allocation preserves the three-to-one remaining ratio");
        near(reallocated.gunClassProbability(GunClass.HEAVY), .25, "Empty family share stays inside the gun category");
        near(reallocated.poolProbability(LootCategory.GUN), .8, "Partially empty gun families preserve top-level category probability");
        TwoStageLootSampler zero = sample(ENTRIES, selection(GunClass.RIFLE, 1));
        check(zero.gunClassProbability(GunClass.SMG) == 0 && zero.itemProbability(SMG) == 0 && zero.gunClassCount(GunClass.SMG) == 1,
                "Explicit zero family remains closed with an available positive item");
        TwoStageLootSampler noEnabledFamily = sample(noRifles, selection(GunClass.RIFLE, 1));
        near(noEnabledFamily.poolProbability(LootCategory.GUN), 0, "All enabled gun families empty makes the gun category empty");
        near(noEnabledFamily.poolProbability(LootCategory.ITEM), 1, "Other positive top-level categories receive empty gun share");
        check(noEnabledFamily.itemProbability(SMG) == 0 && noEnabledFamily.itemProbability(HEAVY) == 0,
                "Zero families remain closed during fallback");
        Document gunOnly = new Document(general(selection(GunClass.RIFLE, 1)), noRifles, GunSettings.defaults(), categories(1, 0));
        TwoStageLootSampler empty = TwoStageLootSampler.prepare(gunOnly, CATEGORY, CLASS);
        CountingRandom untouched = new CountingRandom(9);
        check(empty.isEmpty() && empty.choose(untouched).isEmpty() && untouched.calls == 0,
                "An empty selection consumes no random values and revives no zero category");
        TwoStageLootSampler otherOnly = TwoStageLootSampler.prepare(new Document(general(manual), ENTRIES,
                GunSettings.defaults(), categories(0, 1)), CATEGORY, CLASS);
        near(otherOnly.itemProbability(RIFLE_A), 0, "Top-level gun zero remains closed even with active family probabilities");
        Random draws = new Random(5);
        for (int index = 0; index < 500; index++) check(otherOnly.choose(draws).orElseThrow().equals(ITEM), "Disabled main gun category cannot be selected");
    }

    private static void validation() {
        GunSelection draft = selection(GunClass.RIFLE, .3, GunClass.SMG, .3);
        List<String> errors = draft.validate();
        check(errors.size() == 1 && GunSelection.isTotalError(errors.getFirst())
                && LootConfigModel.isGunClassTotalError(errors.getFirst()), "Explicit class total has a stable draft error marker");
        check(!GunSelection.isTotalError(null) && !GunSelection.isTotalError("range"), "Other errors retain their own validation");
        check(LootConfigModel.validate(doc(ENTRIES, draft)).stream().anyMatch(LootConfigModel::isGunClassTotalError),
                "Real document validation enforces explicit class total");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(doc(ENTRIES, draft))), "Save codec rejects incomplete class total");
        Map<String, Double> close = new LinkedHashMap<>(selection(GunClass.RIFLE, 1).probabilities());
        close.put(GunClass.RIFLE.id(), 1 - 5e-10);
        check(new GunSelection(close).validate().isEmpty(), "Floating-point total tolerance");
        close.put(GunClass.RIFLE.id(), 1 - 2e-9);
        check(new GunSelection(close).validate().stream().anyMatch(GunSelection::isTotalError), "Material total error is rejected");
        for (double invalid : new double[]{-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var bad = new LinkedHashMap<>(selection(GunClass.RIFLE, 1).probabilities()); bad.put(GunClass.SMG.id(), invalid);
            check(new GunSelection(bad).validate().stream().anyMatch(error -> !GunSelection.isTotalError(error)),
                    "Invalid individual class probability is a range error");
        }
        var missing = new LinkedHashMap<>(selection(GunClass.RIFLE, 1).probabilities()); missing.remove(GunClass.OTHER.id());
        check(!new GunSelection(missing).validate().isEmpty(), "All nine explicit classes are required");
        var unknown = new LinkedHashMap<>(selection(GunClass.RIFLE, 1).probabilities()); unknown.put("unknown", 0D);
        check(!new GunSelection(unknown).validate().isEmpty(), "Unknown explicit class key is rejected");
        var source = new LinkedHashMap<>(selection(GunClass.RIFLE, 1).probabilities());
        GunSelection immutable = new GunSelection(source); source.put(GunClass.RIFLE.id(), 0D);
        near(immutable.probabilities().get(GunClass.RIFLE.id()), 1, "Class authoring map is defensively copied");
        immutable(() -> immutable.probabilities().put(GunClass.SMG.id(), .5), "Class probability map is immutable");
    }

    private static void persistence() {
        GunSelection configured = selection(GunClass.RIFLE, .6, GunClass.SMG, .3, GunClass.HEAVY, .1);
        Document original = doc(ENTRIES, configured);
        String json = LootConfigModel.toJson(original);
        check(LootConfigModel.parse(json).equals(original), "Explicit class settings survive the actual document codec");
        Document auto = doc(ENTRIES, GunSelection.defaults());
        check(LootConfigModel.parse(LootConfigModel.toJson(auto)).equals(auto), "Automatic settings survive omitted-null Gson fields");
        var old = JsonParser.parseString(json).getAsJsonObject(); old.getAsJsonObject("general").remove("gunSelection");
        Document migrated = LootConfigModel.parse(old.toString());
        check(migrated.general().gunSelection().isAutomatic() && migrated.items().equals(original.items())
                && migrated.categoryProbabilities().equals(original.categoryProbabilities())
                && migrated.general().zoneSettings().equals(original.general().zoneSettings()), "Old general JSON gains only dynamic class defaults");
        General g = General.defaults();
        check(new General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(), g.samePlaneYTolerance(),
                g.minItemSpacing(), g.lootDensity()).gunSelection().isAutomatic(), "Legacy six-argument General constructor");
        check(new General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(), g.samePlaneYTolerance(),
                g.minItemSpacing(), g.lootDensity(), g.ammoRadiusMin(), g.ammoRadiusMax()).gunSelection().isAutomatic(), "Legacy eight-argument General constructor");
        check(new General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(), g.samePlaneYTolerance(),
                g.minItemSpacing(), g.lootDensity(), g.ammoRadiusMin(), g.ammoRadiusMax(), .5, 2).gunSelection().isAutomatic(), "Legacy ten-argument General constructor");
        check(new General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(), g.samePlaneYTolerance(),
                g.minItemSpacing(), g.lootDensity(), g.ammoRadiusMin(), g.ammoRadiusMax(), .5, 2, g.zoneSettings()).gunSelection().isAutomatic(), "Legacy eleven-argument General constructor");
        var broken = JsonParser.parseString(json).getAsJsonObject();
        broken.getAsJsonObject("general").getAsJsonObject("gunSelection").getAsJsonObject("probabilities").remove("other");
        final String missing = broken.toString(); rejects(() -> LootConfigModel.parse(missing), "Codec requires every manual family");
        broken = JsonParser.parseString(json).getAsJsonObject();
        broken.getAsJsonObject("general").getAsJsonObject("gunSelection").getAsJsonObject("probabilities").addProperty("other", "0");
        final String stringNumber = broken.toString(); rejects(() -> LootConfigModel.parse(stringNumber), "Codec rejects numeric strings");
        broken = JsonParser.parseString(json).getAsJsonObject();
        broken.getAsJsonObject("general").getAsJsonObject("gunSelection").addProperty("mode", "automatic");
        final String unknown = broken.toString(); rejects(() -> LootConfigModel.parse(unknown), "Codec rejects unrelated gun-selection fields");
        broken = JsonParser.parseString(json).getAsJsonObject();
        broken.getAsJsonObject("general").getAsJsonObject("gunSelection").add("probabilities", JsonNull.INSTANCE);
        check(LootConfigModel.parse(broken.toString()).general().gunSelection().isAutomatic(), "Explicit null probabilities are the automatic migration representation");
        String duplicate = json.replace("\"probabilities\": {", "\"probabilities\": {\"rifle\":0.6,");
        check(!duplicate.equals(json), "Duplicate-key fixture targets the actual nested probability object");
        rejects(() -> LootConfigModel.parse(duplicate), "Duplicate class keys are detected before object overwrite");
    }

    private static void seededDraws() {
        Document document = doc(ENTRIES, selection(GunClass.RIFLE, .6, GunClass.SMG, .3, GunClass.HEAVY, .1));
        List<Entry> reversed = new ArrayList<>(ENTRIES); Collections.reverse(reversed);
        TwoStageLootSampler first = TwoStageLootSampler.prepare(document, CATEGORY, CLASS);
        TwoStageLootSampler reordered = sample(reversed, document.general().gunSelection());
        TwoStageLootSampler restored = TwoStageLootSampler.prepare(LootConfigModel.parse(LootConfigModel.toJson(document)), CATEGORY, CLASS);
        List<String> seedA = null;
        for (long seed : new long[]{73, 19_026_1019L}) {
            Random one = new Random(seed), two = new Random(seed), three = new Random(seed);
            List<String> sequence = new ArrayList<>();
            for (int draw = 0; draw < 2_000; draw++) {
                Entry chosen = first.choose(one).orElseThrow();
                check(chosen.equals(reordered.choose(two).orElseThrow()), "Fixed-seed result ignores document item ordering");
                check(chosen.equals(restored.choose(three).orElseThrow()), "Fixed-seed result survives serialization");
                sequence.add(chosen.key());
            }
            if (seedA == null) seedA = sequence;
            else check(!seedA.equals(sequence), "Distinct seeds produce distinct deterministic streams");
        }
        Random draws = new Random(8_104_212);
        Map<String, Integer> observed = new LinkedHashMap<>();
        int size = 60_000;
        for (int draw = 0; draw < size; draw++) observed.merge(first.choose(draws).orElseThrow().key(), 1, Integer::sum);
        for (Entry entry : ENTRIES) check(Math.abs(observed.getOrDefault(entry.key(), 0) / (double) size
                - first.itemProbability(entry)) < .008, "Observed three-stage frequency follows predicted probability " + entry.id());
    }

    private static Entry gun(String path, double weight) { return new Entry("gun", "test:" + path, 1, 1, weight); }
    private static General general(GunSelection selection) {
        General g = General.defaults();
        return new General(g.maxItemsPerPlane(), g.planeSpawnChance(), g.minItemsPerPlane(), g.samePlaneYTolerance(),
                g.minItemSpacing(), g.lootDensity(), g.ammoRadiusMin(), g.ammoRadiusMax(),
                g.companionAttachmentChance(), g.companionAttachmentKinds(), g.zoneSettings(), selection);
    }
    private static Document doc(List<Entry> entries, GunSelection selection) { return new Document(general(selection), entries, GunSettings.defaults(), categories(.8, .2)); }
    private static TwoStageLootSampler sample(List<Entry> entries, GunSelection selection) { return TwoStageLootSampler.prepare(doc(entries, selection), CATEGORY, CLASS); }
    private static Map<String, Double> categories(double gun, double item) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (LootCategory category : LootCategory.values()) values.put(category.id(), 0D);
        values.put(LootCategory.GUN.id(), gun); values.put(LootCategory.ITEM.id(), item); return values;
    }
    private static GunSelection selection(Object... pairs) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (GunClass type : GunClass.values()) values.put(type.id(), 0D);
        for (int index = 0; index < pairs.length; index += 2) values.put(((GunClass) pairs[index]).id(), ((Number) pairs[index + 1]).doubleValue());
        return new GunSelection(values);
    }
    private static Map<GunClass, Integer> counts(Object... pairs) {
        Map<GunClass, Integer> values = new EnumMap<>(GunClass.class);
        for (int index = 0; index < pairs.length; index += 2) values.put((GunClass) pairs[index], ((Number) pairs[index + 1]).intValue());
        return values;
    }
    private static void total(TwoStageLootSampler sampler, List<Entry> entries, String message) { near(entries.stream().mapToDouble(sampler::itemProbability).sum(), 1, message); }
    private static void near(double actual, double expected, String message) { check(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-10, message + ": " + actual + " vs " + expected); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError(message);
    }
    private static void immutable(Runnable action, String message) {
        checks++; try { action.run(); } catch (UnsupportedOperationException expected) { return; } throw new AssertionError(message);
    }
    private static final class CountingRandom extends Random {
        int calls;
        CountingRandom(long seed) { super(seed); }
        @Override public double nextDouble() { calls++; return super.nextDouble(); }
    }
}
