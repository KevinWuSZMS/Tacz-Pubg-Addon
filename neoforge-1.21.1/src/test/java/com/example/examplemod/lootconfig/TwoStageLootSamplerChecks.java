package com.example.examplemod.lootconfig;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Pure category-first sampling and probability checks; does not launch Minecraft or read TaCZ assets. */
public final class TwoStageLootSamplerChecks {
    private static int checks;
    private static final Function<Entry, LootCategory> CLASSIFY = entry -> LootCategory.classify(entry,
            "attachment".equals(entry.kind()) ? entry.id().split(":")[1].split("_")[0] : "");

    public static void main(String[] args) {
        Entry gunA = entry("gun", "gun_a", 20);
        Entry gunB = entry("gun", "gun_b", 10);
        Entry scope = entry("attachment", "scope_a", 900_000);
        Map<String, Double> split = probabilities(LootCategory.GUN, .4, LootCategory.SCOPE, .6);
        Document original = document(List.of(gunA, gunB, scope), split);
        TwoStageLootSampler small = TwoStageLootSampler.prepare(original, CLASSIFY);
        near(small.poolProbability(LootCategory.GUN), .4, "gun category probability ignores attachment total weight");
        near(small.poolProbability(LootCategory.SCOPE), .6, "scope category uses its explicit first-stage probability");
        near(small.withinPoolProbability(gunA), 2D / 3, "pool weights preserve the two-to-one ratio");
        near(small.itemProbability(gunA), .4 * 2 / 3, "overall probability multiplies the two stages");
        near(small.itemProbability(gunB), .4 / 3, "smaller item weight affects only its own pool");
        near(small.itemProbability(scope), .6, "a singleton scope receives the whole scope pool share");
        check(small.poolCount(LootCategory.GUN) == 2 && small.poolCount(LootCategory.SCOPE) == 1,
                "positive finite entry counts are exposed by pool");

        List<Entry> expanded = new ArrayList<>(original.items());
        for (int i = 0; i < 5000; i++) expanded.add(entry("attachment", "scope_added_" + i, i % 5 + 1));
        AtomicInteger classificationCalls = new AtomicInteger();
        TwoStageLootSampler large = TwoStageLootSampler.prepare(document(expanded, split), entry -> {
            classificationCalls.incrementAndGet();
            return CLASSIFY.apply(entry);
        });
        near(large.poolProbability(LootCategory.GUN), small.poolProbability(LootCategory.GUN),
                "adding five thousand entries to another pool cannot change the gun pool probability");
        near(large.itemProbability(gunA), small.itemProbability(gunA),
                "adding many scopes cannot dilute an individual gun's overall probability");
        near(large.withinPoolProbability(gunA), small.withinPoolProbability(gunA),
                "adding another pool does not change within-gun probability");
        check(large.poolCount(LootCategory.SCOPE) == 5001, "expanded pool retains all positive entries");
        Random draws = new Random(1718);
        int gunCount = 0, gunACount = 0;
        for (int i = 0; i < 100_000; i++) {
            Entry selected = large.choose(draws).orElseThrow();
            if (selected.kind().equals("gun")) {
                gunCount++;
                if (selected.equals(gunA)) gunACount++;
            }
        }
        check(gunCount > 39_000 && gunCount < 41_000, "observed gun frequency follows forty percent despite a huge scope pool");
        check(gunACount > gunCount * .65 && gunACount < gunCount * .685,
                "observed gun choice follows the configured within-pool two-to-one ratio");
        check(classificationCalls.get() == expanded.size(), "classification runs once at preparation, never for each draw");

        Entry disabledScope = entry("attachment", "scope_disabled", 0);
        TwoStageLootSampler emptyScope = TwoStageLootSampler.prepare(document(List.of(gunA, disabledScope), split), CLASSIFY);
        near(emptyScope.poolProbability(LootCategory.GUN), 1, "an empty positive category redistributes its share proportionally");
        near(emptyScope.poolProbability(LootCategory.SCOPE), 0, "an empty category has zero actual probability");
        near(emptyScope.itemProbability(disabledScope), 0, "zero item weight stays disabled");
        check(emptyScope.poolCount(LootCategory.SCOPE) == 0, "zero item weight does not make a nonempty category");
        Entry ordinary = entry("item", "ordinary", 1);
        TwoStageLootSampler proportional = TwoStageLootSampler.prepare(document(List.of(gunA, disabledScope, ordinary),
                probabilities(LootCategory.GUN, .4, LootCategory.SCOPE, .4, LootCategory.ITEM, .2)), CLASSIFY);
        near(proportional.poolProbability(LootCategory.GUN), 2D / 3, "empty-pool redistribution preserves the remaining category ratio");
        near(proportional.poolProbability(LootCategory.ITEM), 1D / 3, "a small remaining category retains its proportional share");

        Entry ammo = entry("ammo", "ammo_a", 100);
        TwoStageLootSampler forbiddenAmmo = TwoStageLootSampler.prepare(document(List.of(ammo, scope),
                probabilities(LootCategory.AMMO, 0, LootCategory.SCOPE, 1)), CLASSIFY);
        near(forbiddenAmmo.poolProbability(LootCategory.AMMO), 0, "first-stage zero is never revived by positive ammo weight");
        near(forbiddenAmmo.withinPoolProbability(ammo), 1, "disabled-category entries still expose their within-pool ratio");
        check(forbiddenAmmo.poolCount(LootCategory.AMMO) == 1, "disabled-category pool size remains visible for authoring");
        for (int i = 0; i < 1000; i++) check(forbiddenAmmo.choose(draws).orElseThrow().equals(scope), "disabled ammo cannot be drawn");

        General dense = new General(1, 1, 1, .5, 0, 1);
        Document noSelectable = new Document(dense, List.of(gunA, disabledScope), GunSettings.defaults(),
                probabilities(LootCategory.SCOPE, 1));
        TwoStageLootSampler empty = TwoStageLootSampler.prepare(noSelectable, CLASSIFY);
        CountingRandom untouched = new CountingRandom();
        check(empty.isEmpty() && empty.choose(untouched).isEmpty(), "all active pools empty returns Optional.empty");
        check(untouched.draws == 0, "empty sampler consumes no selection draws");
        near(empty.itemProbability(gunA), 0, "a nonempty zero-probability pool remains disabled when all positive pools are empty");
        var floors = List.of(new LootSpawnRules.FloorSample(new BlockPos(0, 64, 0), 65));
        check(LootSpawnRules.plan(floors, noSelectable, 1, new Random(1), CLASSIFY).isEmpty(),
                "region planning returns no items rather than reviving a disabled category");
        Document scopeOnly = new Document(dense, List.of(gunA, scope), GunSettings.defaults(), probabilities(LootCategory.SCOPE, 1));
        var scopePlan = LootSpawnRules.plan(floors, scopeOnly, 1, new Random(9), CLASSIFY);
        check(scopePlan.size() == 1 && scopePlan.getFirst().entry().equals(scope),
                "region planning uses the caller's semantic attachment classifier");

        TwoStageLootSampler draft = TwoStageLootSampler.prepare(document(List.of(gunA, scope),
                probabilities(LootCategory.GUN, .2, LootCategory.SCOPE, .2)), CLASSIFY);
        near(draft.poolProbability(LootCategory.GUN), .5, "UI draft preview can normalize a temporary total below one");
        TwoStageLootSampler allZeroDraft = TwoStageLootSampler.prepare(document(List.of(gunA, scope), probabilities()), CLASSIFY);
        check(allZeroDraft.isEmpty(), "all-zero draft has no selectable pool");

        Entry hugeA = entry("gun", "huge_a", Double.MAX_VALUE);
        Entry hugeB = entry("gun", "huge_b", Double.MAX_VALUE / 2);
        TwoStageLootSampler huge = TwoStageLootSampler.prepare(document(List.of(hugeA, hugeB), probabilities(LootCategory.GUN, 1)));
        near(huge.itemProbability(hugeA), 2D / 3, "huge finite weights normalize without overflow");
        near(huge.itemProbability(hugeB), 1D / 3, "huge relative weights keep their ratio");
        Entry tinyA = entry("gun", "tiny_a", Double.MIN_VALUE);
        Entry tinyB = entry("gun", "tiny_b", Double.MIN_VALUE);
        TwoStageLootSampler tiny = TwoStageLootSampler.prepare(document(List.of(tinyA, tinyB), probabilities(LootCategory.GUN, 1)));
        near(tiny.itemProbability(tinyA), .5, "subnormal positive weights do not underflow before normalization");
        near(tiny.itemProbability(tinyB), .5, "equal tiny weights remain equally likely");
        TwoStageLootSampler invalidWeights = TwoStageLootSampler.prepare(document(List.of(
                entry("gun", "bad_nan", Double.NaN), entry("gun", "bad_inf", Double.POSITIVE_INFINITY),
                entry("gun", "bad_negative", -1), gunA), probabilities(LootCategory.GUN, 1)));
        check(invalidWeights.poolCount(LootCategory.GUN) == 1, "invalid nonfinite and negative weights cannot participate");
        near(invalidWeights.itemProbability(gunA), 1, "invalid weights do not poison the valid pool denominator");

        Entry unknown = entry("attachment", "unknown_part", 1);
        TwoStageLootSampler unknownPool = TwoStageLootSampler.prepare(document(List.of(unknown),
                probabilities(LootCategory.OTHER_ATTACHMENT, 1)));
        near(unknownPool.poolProbability(LootCategory.OTHER_ATTACHMENT), 1, "default classifier preserves unknown attachments in their explicit pool");
        Entry laser = entry("attachment", "laser_a", 1);
        TwoStageLootSampler laserPool = TwoStageLootSampler.prepare(document(List.of(laser), probabilities(LootCategory.LASER, 1)), CLASSIFY);
        near(laserPool.poolProbability(LootCategory.LASER), 1, "semantic laser has a dedicated pool despite a possible native grip slot");

        Random left = new Random(6283), right = new Random(6283);
        for (int i = 0; i < 1000; i++) check(small.choose(left).equals(small.choose(right)), "sampling remains deterministic for a fixed seed");
        double totalProbability = original.items().stream().mapToDouble(small::itemProbability).sum();
        near(totalProbability, 1, "actual item probabilities sum to one across active pools");
        System.out.println("TwoStageLootSamplerChecks: " + checks + " assertions passed");
    }

    private static Entry entry(String kind, String path, double weight) {
        return new Entry(kind, "test:" + path, 1, 1, weight);
    }

    private static Document document(List<Entry> entries, Map<String, Double> probabilities) {
        return new Document(General.defaults(), entries, GunSettings.defaults(), probabilities);
    }

    private static Map<String, Double> probabilities(Object... pairs) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (LootCategory category : LootCategory.values()) result.put(category.id(), 0D);
        for (int i = 0; i < pairs.length; i += 2) result.put(((LootCategory) pairs[i]).id(), ((Number) pairs[i + 1]).doubleValue());
        return result;
    }

    private static final class CountingRandom extends Random {
        int draws;
        @Override public double nextDouble() { draws++; return super.nextDouble(); }
    }

    private static void near(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-12, message + ": " + actual + " vs " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
