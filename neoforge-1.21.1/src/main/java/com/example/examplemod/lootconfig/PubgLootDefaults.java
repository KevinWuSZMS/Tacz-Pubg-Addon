package com.example.examplemod.lootconfig;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PubgLootDefaults {
    private static final Map<String, LootConfigModel.TierProfile> PROFILES = load();
    private static final Map<String, Double> LEGACY = Map.of(
            "gun", .40, "ammo", 0., "scope", .15, "magazine", .15, "muzzle", .10,
            "grip", .08, "stock", .05, "laser", .02, "other_attachment", 0., "item", .05);

    private PubgLootDefaults() {}

    public static LootConfigModel.TierProfile profile(LootTier tier) { return PROFILES.get(tier.id()); }
    public static boolean legacyCategories(JsonObject json) { return json.keySet().equals(LEGACY.keySet()); }
    public static boolean legacyDefaults(JsonObject json) {
        return legacyCategories(json) && LEGACY.entrySet().stream().allMatch(e ->
                json.get(e.getKey()).isJsonPrimitive() && json.get(e.getKey()).getAsJsonPrimitive().isNumber()
                        && json.get(e.getKey()).getAsDouble() == e.getValue());
    }

    /** Preserve custom pools' mass; move their old item weights into the corresponding new categories. */
    public static Map<String, Double> migrate(Map<String, Double> old, List<LootConfigModel.Entry> items) {
        var result = new LinkedHashMap<String, Double>();
        for (LootCategory category : LootCategory.values()) result.put(category.id(), old.getOrDefault(category.id(), 0.));
        double itemMass = old.get("item");
        // Invalid input must reach normal validation, never disappear during migration.
        if (!Double.isFinite(itemMass) || itemMass < 0 || itemMass > 1) return result;
        var weights = new LinkedHashMap<String, Double>();
        for (var item : items) if ("item".equals(item.kind()) && Double.isFinite(item.weight()) && item.weight() > 0)
            weights.merge(LootCategory.itemCategory(item.id()).id(), item.weight(), Double::sum);
        if (weights.isEmpty()) {
            for (var category : List.of(LootCategory.HELMET, LootCategory.VEST, LootCategory.BACKPACK,
                    LootCategory.THROWABLE, LootCategory.MEDICAL)) weights.put(category.id(), category.defaultProbability());
        }
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        result.put("item", 0.);
        weights.forEach((category, weight) -> result.put(category, itemMass * weight / total));
        return result;
    }

    private static Map<String, LootConfigModel.TierProfile> load() {
        try (var stream = PubgLootDefaults.class.getResourceAsStream("/assets/tacz_pubg/pubg_loot_defaults.json")) {
            if (stream == null) throw new IllegalStateException("Missing bundled PUBG loot defaults");
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var profiles = new LinkedHashMap<String, LootConfigModel.TierProfile>();
            for (LootTier tier : LootTier.values()) {
                var json = root.getAsJsonObject("profiles").getAsJsonObject(tier.id());
                var categories = new LinkedHashMap<String, Double>();
                for (LootCategory category : LootCategory.values())
                    categories.put(category.id(), json.getAsJsonObject("categoryProbabilities").get(category.id()).getAsDouble());
                var items = new ArrayList<LootConfigModel.Entry>();
                for (var element : json.getAsJsonArray("items")) {
                    var item = element.getAsJsonObject();
                    items.add(new LootConfigModel.Entry("item", item.get("id").getAsString(),
                            item.get("minCount").getAsInt(), item.get("maxCount").getAsInt(), item.get("weight").getAsDouble()));
                }
                profiles.put(tier.id(), new LootConfigModel.TierProfile(items, categories));
            }
            return Collections.unmodifiableMap(profiles);
        } catch (Exception error) {
            throw new IllegalStateException("Invalid bundled PUBG loot defaults", error);
        }
    }
}
