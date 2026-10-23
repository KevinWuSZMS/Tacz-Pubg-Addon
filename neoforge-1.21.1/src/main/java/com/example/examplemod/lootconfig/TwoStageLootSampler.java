package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/** Immutable category-first sampler; independent of Minecraft assets and world access. */
public final class TwoStageLootSampler {
    private record ItemChance(LootCategory category, double withinPool) {}
    private final Map<LootCategory, Pool> pools;
    private final Map<LootCategory, Double> categoryChances;
    private final Map<String, ItemChance> itemChances;
    private final List<LootCategory> activeCategories;
    private final double[] categoryCumulative;
    private final double categoryTotal;
    private final Map<GunClass, Pool> gunPools;
    private final Map<GunClass, Double> gunChances;
    private final Map<String, GunClass> entryGunClasses;
    private final List<GunClass> activeGunClasses;
    private final double[] gunCumulative;
    private final double gunTotal;

    private TwoStageLootSampler(Document document, Function<Entry, LootCategory> classifier,
            Function<Entry, GunClass> gunClassifier, Map<GunClass, Integer> catalogCounts) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(classifier, "classifier");
        Objects.requireNonNull(gunClassifier, "gunClassifier");
        EnumMap<LootCategory, List<Entry>> grouped = new EnumMap<>(LootCategory.class);
        EnumMap<GunClass, List<Entry>> guns = new EnumMap<>(GunClass.class);
        EnumMap<GunClass, Integer> counts = new EnumMap<>(GunClass.class);
        Map<String, GunClass> entryClasses = new HashMap<>();
        for (GunClass type : GunClass.values()) guns.put(type, new ArrayList<>());
        for (LootCategory category : LootCategory.values()) grouped.put(category, new ArrayList<>());
        for (Entry entry : document.items()) {
            if ("gun".equals(entry.kind())) {
                GunClass type = Objects.requireNonNull(gunClassifier.apply(entry), Texts.tr("error.tacz_pubg.two_stage_loot_sampler.gun_class"));
                entryClasses.put(entry.key(), type);
                counts.merge(type, 1, Integer::sum);
            }
            // Invalid or disabled entry weights cannot silently become selectable in either stage.
            if (!Double.isFinite(entry.weight()) || entry.weight() <= 0) continue;
            LootCategory category = Objects.requireNonNull(classifier.apply(entry), Texts.tr("error.tacz_pubg.two_stage_loot_sampler.loot_category"));
            grouped.get(category).add(entry);
            if (category == LootCategory.GUN) guns.get(entryClasses.getOrDefault(entry.key(), GunClass.OTHER)).add(entry);
        }
        Map<GunClass, Double> gunConfigured = document.general().gunSelection()
                .configuredProbabilities(catalogCounts == null ? counts : catalogCounts);
        EnumMap<GunClass, Pool> preparedGuns = new EnumMap<>(GunClass.class);
        List<GunClass> activeGuns = new ArrayList<>();
        double gunSum = 0;
        for (GunClass type : GunClass.values()) {
            Pool pool = new Pool(guns.get(type));
            preparedGuns.put(type, pool);
            if (!pool.entries.isEmpty() && gunConfigured.get(type) > 0) {
                activeGuns.add(type);
                gunSum += gunConfigured.get(type);
            }
        }
        gunTotal = gunSum;
        gunCumulative = new double[activeGuns.size()];
        EnumMap<GunClass, Double> actualGuns = new EnumMap<>(GunClass.class);
        for (GunClass type : GunClass.values()) actualGuns.put(type, 0D);
        double gunAccumulator = 0;
        for (int i = 0; i < activeGuns.size(); i++) {
            GunClass type = activeGuns.get(i);
            gunCumulative[i] = gunAccumulator += gunConfigured.get(type);
            actualGuns.put(type, gunConfigured.get(type) / gunSum);
        }
        gunPools = Map.copyOf(preparedGuns);
        gunChances = Map.copyOf(actualGuns);
        entryGunClasses = Map.copyOf(entryClasses);
        activeGunClasses = List.copyOf(activeGuns);
        EnumMap<LootCategory, Pool> prepared = new EnumMap<>(LootCategory.class);
        EnumMap<LootCategory, Double> configured = new EnumMap<>(LootCategory.class);
        List<LootCategory> active = new ArrayList<>();
        Map<String, ItemChance> chances = new HashMap<>();
        double total = 0;
        for (LootCategory category : LootCategory.values()) {
            Pool pool = new Pool(grouped.get(category));
            prepared.put(category, pool);
            Double probability = document.categoryProbabilities().get(category.id());
            if (probability == null || !Double.isFinite(probability) || probability < 0 || probability > 1)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.two_stage_loot_sampler.category_probability_between", category.id()));
            configured.put(category, probability);
            for (int i = 0; i < pool.entries.size(); i++) {
                Entry entry = pool.entries.get(i);
                double within = pool.scaledWeights[i] / pool.total;
                if (category == LootCategory.GUN) {
                    GunClass type = entryClasses.getOrDefault(entry.key(), GunClass.OTHER);
                    Pool gunPool = preparedGuns.get(type);
                    within = actualGuns.get(type) * gunPool.chance(entry);
                }
                if (chances.put(entry.key(), new ItemChance(category, within)) != null)
                    throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.two_stage_loot_sampler.duplicate_loot_entry", entry.key()));
            }
            // Zero-probability categories stay disabled, even if every positive category is empty.
            if (probability > 0 && !pool.entries.isEmpty() && (category != LootCategory.GUN || !activeGuns.isEmpty())) {
                active.add(category);
                total += probability;
            }
        }
        pools = Map.copyOf(prepared);
        itemChances = Map.copyOf(chances);
        activeCategories = List.copyOf(active);
        categoryTotal = total;
        categoryCumulative = new double[active.size()];
        EnumMap<LootCategory, Double> actual = new EnumMap<>(LootCategory.class);
        for (LootCategory category : LootCategory.values()) actual.put(category, 0D);
        double cumulative = 0;
        for (int i = 0; i < active.size(); i++) {
            LootCategory category = active.get(i);
            double probability = configured.get(category);
            categoryCumulative[i] = cumulative += probability;
            actual.put(category, probability / total);
        }
        categoryChances = Map.copyOf(actual);
    }

    public static TwoStageLootSampler prepare(Document document) {
        return prepare(document, entry -> LootCategory.classify(entry, ""));
    }

    /** The caller can inject a cached semantic classifier; preparation never looks up server assets. */
    public static TwoStageLootSampler prepare(Document document, Function<Entry, LootCategory> classifier) {
        return prepare(document, classifier, entry -> GunClass.OTHER);
    }

    public static TwoStageLootSampler prepare(Document document, Function<Entry, LootCategory> classifier,
            Function<Entry, GunClass> gunClassifier) {
        return new TwoStageLootSampler(document, classifier, gunClassifier, null);
    }

    public static TwoStageLootSampler prepare(Document document, Function<Entry, LootCategory> classifier,
            Function<Entry, GunClass> gunClassifier, Map<GunClass, Integer> catalogCounts) {
        return new TwoStageLootSampler(document, classifier, gunClassifier, Map.copyOf(catalogCounts));
    }

    public Optional<Entry> choose(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        if (isEmpty()) return Optional.empty();
        int categoryIndex = locate(categoryCumulative, random.nextDouble() * categoryTotal);
        LootCategory category = activeCategories.get(categoryIndex);
        if (category == LootCategory.GUN) {
            int index = locate(gunCumulative, random.nextDouble() * gunTotal);
            return Optional.of(gunPools.get(activeGunClasses.get(index)).choose(random));
        }
        return Optional.of(pools.get(category).choose(random));
    }

    /** Actual unconditional item probability after empty-pool renormalization. */
    public double itemProbability(Entry entry) {
        ItemChance item = itemChances.get(Objects.requireNonNull(entry, "entry").key());
        return item == null ? 0 : poolProbability(item.category()) * item.withinPool();
    }

    /** Positive entry weights normalized within their own pool, including a category configured to zero. */
    public double withinPoolProbability(Entry entry) {
        ItemChance item = itemChances.get(Objects.requireNonNull(entry, "entry").key());
        return item == null ? 0 : item.withinPool();
    }

    /** Actual category probability; disabled and empty pools return zero. */
    public double poolProbability(LootCategory category) {
        return categoryChances.get(Objects.requireNonNull(category, "category"));
    }

    /** Positive finite weight entries in the category, including a category configured to zero. */
    public int poolCount(LootCategory category) {
        return pools.get(Objects.requireNonNull(category, "category")).entries.size();
    }

    public double gunClassProbability(GunClass type) { return gunChances.get(Objects.requireNonNull(type)); }
    public int gunClassCount(GunClass type) { return gunPools.get(Objects.requireNonNull(type)).entries.size(); }
    public double withinGunClassProbability(Entry entry) {
        GunClass type = entryGunClasses.get(Objects.requireNonNull(entry).key());
        return type == null ? 0 : gunPools.get(type).chance(entry);
    }

    public boolean isEmpty() { return activeCategories.isEmpty(); }

    private static int locate(double[] cumulative, double value) {
        int low = 0, high = cumulative.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (value < cumulative[middle]) high = middle; else low = middle + 1;
        }
        return low;
    }

    private static final class Pool {
        final List<Entry> entries;
        final double[] scaledWeights;
        final double[] cumulative;
        final double total;
        final Map<String, Double> chances;

        Pool(List<Entry> configured) {
            entries = configured.stream().sorted(Comparator.comparing(Entry::key)).toList();
            double maximum = entries.stream().mapToDouble(Entry::weight).max().orElse(1);
            scaledWeights = new double[entries.size()];
            cumulative = new double[entries.size()];
            double sum = 0;
            for (int i = 0; i < entries.size(); i++) {
                // Scale before adding so multiple huge finite weights cannot overflow the denominator.
                scaledWeights[i] = entries.get(i).weight() / maximum;
                cumulative[i] = sum += scaledWeights[i];
            }
            total = sum;
            Map<String, Double> values = new HashMap<>();
            for (int i = 0; i < entries.size(); i++) values.put(entries.get(i).key(), scaledWeights[i] / total);
            chances = Map.copyOf(values);
        }

        double chance(Entry entry) { return chances.getOrDefault(entry.key(), 0D); }

        Entry choose(RandomGenerator random) {
            return entries.get(locate(cumulative, random.nextDouble() * total));
        }
    }
}
