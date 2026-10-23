package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import net.minecraft.resources.ResourceLocation;
import com.example.examplemod.zones.ZoneSettings;
import com.example.examplemod.operatorpermissions.OperatorPermissions;

/** Immutable authoring configuration shared by the server and configuration screen. */
public final class LootConfigModel {
    /** A transfer budget, not an item-count limit. */
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> KINDS = Set.of("gun", "ammo", "attachment", "item");
    public static final String DEFAULT_GUN_PROFILE = "default";
    public static final String RESEARCHED_GUN_PROFILE = "researched";
    public static final String INITIAL = "initial";
    public static final String EXPERIMENTAL = "experimental";
    public static final double CATEGORY_TOTAL_TOLERANCE = 1e-9;
    public static final String CATEGORY_TOTAL_ERROR_PREFIX = Texts.tr("message.tacz_pubg.loot_config_model.category_probability_total");
    public static final int AMMO_SUPPLY_CHOICES = 3;
    public static final int MAX_AMMO_ROUNDS_PER_GROUP = 4096;
    public static final int MAX_AMMO_GROUPS = 64;
    public static final double AMMO_PROBABILITY_TOLERANCE = 1e-9;
    public static final String SELF_AMMO = "self";

    private LootConfigModel() {}

    public record General(int maxItemsPerPlane, double planeSpawnChance, int minItemsPerPlane,
            double samePlaneYTolerance, double minItemSpacing, double lootDensity,
            double ammoRadiusMin, double ammoRadiusMax,
            double companionAttachmentChance, int companionAttachmentKinds, ZoneSettings zoneSettings, GunSelection gunSelection,
            OperatorPermissions operatorPermissions) {
        public General {
            Objects.requireNonNull(zoneSettings, "zoneSettings");
            Objects.requireNonNull(gunSelection, "gunSelection");
            Objects.requireNonNull(operatorPermissions, "operatorPermissions");
        }
        public General(int maxItemsPerPlane, double planeSpawnChance, int minItemsPerPlane,
                double samePlaneYTolerance, double minItemSpacing, double lootDensity,
                double ammoRadiusMin, double ammoRadiusMax, double companionAttachmentChance,
                int companionAttachmentKinds, ZoneSettings zoneSettings, GunSelection gunSelection) {
            this(maxItemsPerPlane, planeSpawnChance, minItemsPerPlane, samePlaneYTolerance,
                    minItemSpacing, lootDensity, ammoRadiusMin, ammoRadiusMax, companionAttachmentChance,
                    companionAttachmentKinds, zoneSettings, gunSelection, OperatorPermissions.defaults());
        }
        public General withOperatorPermissions(OperatorPermissions permissions) {
            return new General(maxItemsPerPlane, planeSpawnChance, minItemsPerPlane, samePlaneYTolerance,
                    minItemSpacing, lootDensity, ammoRadiusMin, ammoRadiusMax, companionAttachmentChance,
                    companionAttachmentKinds, zoneSettings, gunSelection, permissions);
        }
        public General(int maxItemsPerPlane, double planeSpawnChance, int minItemsPerPlane,
                double samePlaneYTolerance, double minItemSpacing, double lootDensity,
                double ammoRadiusMin, double ammoRadiusMax,
                double companionAttachmentChance, int companionAttachmentKinds, ZoneSettings zoneSettings) {
            this(maxItemsPerPlane, planeSpawnChance, minItemsPerPlane, samePlaneYTolerance,
                    minItemSpacing, lootDensity, ammoRadiusMin, ammoRadiusMax,
                    companionAttachmentChance, companionAttachmentKinds, zoneSettings, GunSelection.defaults());
        }
        public General(int maxItemsPerPlane, double planeSpawnChance, int minItemsPerPlane,
                double samePlaneYTolerance, double minItemSpacing, double lootDensity,
                double ammoRadiusMin, double ammoRadiusMax,
                double companionAttachmentChance, int companionAttachmentKinds) {
            this(maxItemsPerPlane, planeSpawnChance, minItemsPerPlane, samePlaneYTolerance,
                    minItemSpacing, lootDensity, ammoRadiusMin, ammoRadiusMax,
                    companionAttachmentChance, companionAttachmentKinds, ZoneSettings.defaults());
        }
        public General(int maxItemsPerPlane, double planeSpawnChance, int minItemsPerPlane,
                double samePlaneYTolerance, double minItemSpacing, double lootDensity,
                double ammoRadiusMin, double ammoRadiusMax) {
            this(maxItemsPerPlane, planeSpawnChance, minItemsPerPlane, samePlaneYTolerance,
                    minItemSpacing, lootDensity, ammoRadiusMin, ammoRadiusMax, 0.5, 2);
        }
        public General(int maxItemsPerPlane, double planeSpawnChance, int minItemsPerPlane,
                double samePlaneYTolerance, double minItemSpacing, double lootDensity) {
            this(maxItemsPerPlane, planeSpawnChance, minItemsPerPlane, samePlaneYTolerance,
                    minItemSpacing, lootDensity, 1, 1.5);
        }
        public static General defaults() {
            return new General(Integer.MAX_VALUE, 1, 0, 0.5, 2, 0.1, 1, 1.5);
        }
    }

    /** Exactly one distinct item; zero weight disables its independent selection. */
    public record Entry(String kind, String id, int minCount, int maxCount, double weight,
            GunAmmoSupply ammoSupply) {
        /** Null keeps the gun's server-derived default dynamic through resource/profile reloads. */
        public Entry(String kind, String id, int minCount, int maxCount, double weight) {
            this(kind, id, minCount, maxCount, weight, null);
        }
        public String key() { return kind + ":" + id; }
        public Entry withWeight(double value) { return new Entry(kind, id, minCount, maxCount, value, ammoSupply); }
    }

    public record AmmoOption(String ammoId, int groups, double probability) {}

    /** One weighted draw selects an entire ammo/group-count scheme. */
    public record GunAmmoSupply(List<AmmoOption> ammoOptions, int roundsPerGroup) {
        public GunAmmoSupply {
            ammoOptions = List.copyOf(Objects.requireNonNull(ammoOptions, "ammoOptions"));
        }
        public static GunAmmoSupply defaults(int roundsPerGroup) {
            return defaults(roundsPerGroup, false);
        }
        public static GunAmmoSupply defaults(int roundsPerGroup, boolean rifleFamily) {
            return new GunAmmoSupply(List.of(new AmmoOption(SELF_AMMO, 1, rifleFamily ? .3 : .6),
                    new AmmoOption(SELF_AMMO, 2, rifleFamily ? .6 : .3), new AmmoOption(SELF_AMMO, 3, .1)),
                    roundsPerGroup);
        }
    }

    public enum GunSettingCategory {
        BALANCE("balance", Texts.tr("message.tacz_pubg.loot_config_model.balance_adjustments")), SCOPES("scopes", Texts.tr("message.tacz_pubg.loot_category.scopes")), AMMO("ammo", Texts.tr("message.tacz_pubg.loot_config_model.ammo")),
        MUZZLE("muzzle", Texts.tr("message.tacz_pubg.loot_category.muzzles")), LASER("laser", Texts.tr("message.tacz_pubg.loot_category.lasers")), GRIP("grip", Texts.tr("message.tacz_pubg.loot_category.grips")),
        STOCK("stock", Texts.tr("message.tacz_pubg.loot_category.stocks")), MAGAZINE("magazine", Texts.tr("message.tacz_pubg.loot_category.magazines"));

        private final String id, label;
        GunSettingCategory(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        public String label() { return label; }
    }

    /** Independent authoring choices; a stored experimental choice need not have a runtime implementation. */
    public record GunSettings(String balance, String scopes, String ammo, String muzzle,
            String laser, String grip, String stock, String magazine, boolean usePubgOriginalIcons,
            com.example.examplemod.inventory.InventorySettings inventory, boolean lowPolyModels) {
        public GunSettings {
            Objects.requireNonNull(inventory);
        }
        public GunSettings(String balance, String scopes, String ammo, String muzzle,
                String laser, String grip, String stock, String magazine, boolean usePubgOriginalIcons) {
            this(balance, scopes, ammo, muzzle, laser, grip, stock, magazine, usePubgOriginalIcons,
                    com.example.examplemod.inventory.InventorySettings.defaults());
        }
        public GunSettings(String balance, String scopes, String ammo, String muzzle, String laser, String grip,
                String stock, String magazine, boolean usePubgOriginalIcons,
                com.example.examplemod.inventory.InventorySettings inventory) {
            this(balance, scopes, ammo, muzzle, laser, grip, stock, magazine, usePubgOriginalIcons, inventory, false);
        }
        public GunSettings withLowPolyModels(boolean value) {
            return new GunSettings(balance, scopes, ammo, muzzle, laser, grip, stock, magazine, usePubgOriginalIcons, inventory, value);
        }
        public GunSettings(String balance, String scopes, String ammo, String muzzle,
                String laser, String grip, String stock, String magazine) {
            this(balance, scopes, ammo, muzzle, laser, grip, stock, magazine, false);
        }
        public GunSettings withOriginalIcons(boolean enabled) {
            return new GunSettings(balance, scopes, ammo, muzzle, laser, grip, stock, magazine, enabled, inventory, lowPolyModels);
        }
        public GunSettings withInventory(com.example.examplemod.inventory.InventorySettings value) {
            return new GunSettings(balance, scopes, ammo, muzzle, laser, grip, stock, magazine, usePubgOriginalIcons, value, lowPolyModels);
        }
        public static GunSettings defaults() {
            return new GunSettings(INITIAL, INITIAL, INITIAL, INITIAL, INITIAL, INITIAL, INITIAL, INITIAL);
        }
        public static GunSettings fromLegacyProfile(String profile) {
            if (!Set.of(DEFAULT_GUN_PROFILE, RESEARCHED_GUN_PROFILE).contains(profile))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.legacy_gun_profile_default_researched"));
            return RESEARCHED_GUN_PROFILE.equals(profile)
                    ? new GunSettings(EXPERIMENTAL, EXPERIMENTAL, INITIAL, INITIAL, INITIAL, INITIAL, INITIAL, INITIAL)
                    : defaults();
        }
        public String value(GunSettingCategory category) {
            return switch (category) {
                case BALANCE -> balance; case SCOPES -> scopes; case AMMO -> ammo; case MUZZLE -> muzzle;
                case LASER -> laser; case GRIP -> grip; case STOCK -> stock; case MAGAZINE -> magazine;
            };
        }
        public boolean isExperimental(GunSettingCategory category) { return EXPERIMENTAL.equals(value(category)); }
        public GunSettings withValue(GunSettingCategory category, String value) {
            return new GunSettings(category == GunSettingCategory.BALANCE ? value : balance,
                    category == GunSettingCategory.SCOPES ? value : scopes,
                    category == GunSettingCategory.AMMO ? value : ammo,
                    category == GunSettingCategory.MUZZLE ? value : muzzle,
                    category == GunSettingCategory.LASER ? value : laser,
                    category == GunSettingCategory.GRIP ? value : grip,
                    category == GunSettingCategory.STOCK ? value : stock,
                    category == GunSettingCategory.MAGAZINE ? value : magazine, usePubgOriginalIcons, inventory, lowPolyModels);
        }
    }

    public record TierProfile(List<Entry> items, Map<String, Double> categoryProbabilities) {
        public TierProfile {
            items = List.copyOf(items);
            categoryProbabilities = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(categoryProbabilities));
        }
    }

    public record Document(General general, List<Entry> items, GunSettings gunSettings,
            Map<String, Double> categoryProbabilities, Map<String, TierProfile> tierProfiles) {
        public Document(General general, List<Entry> items, GunSettings gunSettings, Map<String, Double> categories) {
            this(general, items, gunSettings, categories, inheritedTierProfiles(items, categories));
        }
        public Document(General general, List<Entry> items) {
            this(general, items, GunSettings.defaults());
        }
        public Document(General general, List<Entry> items, GunSettings gunSettings) {
            this(general, items, gunSettings, defaultCategoryProbabilities());
        }
        public Document(General general, List<Entry> items, String legacyProfile) {
            this(general, items, GunSettings.fromLegacyProfile(legacyProfile));
        }
        public Document {
            Objects.requireNonNull(general, "general");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            Objects.requireNonNull(gunSettings, "gunSettings");
            // Drafts may temporarily total something other than 100%; only final
            // validation/parse enforces normalization. Copy so editing the input
            // map can never mutate an already captured generation document.
            categoryProbabilities = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                    Objects.requireNonNull(categoryProbabilities, "categoryProbabilities")));
            tierProfiles = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(Objects.requireNonNull(tierProfiles, "tierProfiles")));
        }
        public TierProfile tier(LootTier tier) {
            return tierProfiles.getOrDefault(tier.id(), new TierProfile(items, categoryProbabilities));
        }
        public Document forTier(LootTier tier) {
            var profile = tier(tier);
            return new Document(general, profile.items(), gunSettings, profile.categoryProbabilities(), Map.of());
        }
        public Document withTier(LootTier tier, TierProfile profile) {
            var profiles = new java.util.LinkedHashMap<>(tierProfiles);
            profiles.put(tier.id(), profile);
            return new Document(general, items, gunSettings, categoryProbabilities, profiles);
        }
        /** Legacy read adapter; compatibility is now independently selected under gunSettings.scopes. */
        public String gunBalanceProfile() { return gunSettings.isExperimental(GunSettingCategory.BALANCE)
                ? RESEARCHED_GUN_PROFILE : DEFAULT_GUN_PROFILE; }
    }

    public static Map<String, TierProfile> inheritedTierProfiles(List<Entry> items, Map<String, Double> categories) {
        Map<String, TierProfile> profiles = new java.util.LinkedHashMap<>();
        for (LootTier tier : LootTier.values()) profiles.put(tier.id(), new TierProfile(items, categories));
        return profiles;
    }

    public static Document defaultDocument() { return new Document(General.defaults(), List.of()); }
    public static Map<String, Double> defaultCategoryProbabilities() { return LootCategory.defaultProbabilities(); }
    public static boolean isCategoryTotalError(String message) {
        return message != null && message.startsWith(CATEGORY_TOTAL_ERROR_PREFIX);
    }
    public static boolean isGunClassTotalError(String message) { return GunSelection.isTotalError(message); }
    public static double categoryProbabilityTotal(Map<String, Double> probabilities) {
        if (probabilities == null) return Double.NaN;
        double sum = 0;
        for (Double value : probabilities.values()) {
            if (value == null || !Double.isFinite(value)) return Double.NaN;
            sum += value;
        }
        return sum;
    }
    public static String toJson(Document document) { return GSON.toJson(document); }

    public static Document parse(String json) {
        try {
            JsonObject root = object(readJson(json), Texts.tr("message.tacz_pubg.loot_config_model.configuration"));
            boolean legacy = root.has("groups") && !root.has("items");
            keys(root, legacy ? Set.of("general", "groups", "gunBalanceProfile", "gunSettings", "categoryProbabilities", "tierProfiles")
                    : Set.of("general", "items", "gunBalanceProfile", "gunSettings", "categoryProbabilities", "tierProfiles"), Texts.tr("message.tacz_pubg.loot_config_model.configuration"));
            if (root.has("gunSettings") && root.has("gunBalanceProfile"))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.use_gunsettings_gun_settings_use_legacy_gunbalanceprofile_field"));
            JsonObject g = object(required(root, "general"), "general");
            keys(g, legacy ? Set.of("maxGroupsPerPlane", "planeSpawnChance", "minGroupsPerPlane",
                    "samePlaneYTolerance", "minGroupSpacing", "lootDensity")
                    : Set.of("maxItemsPerPlane", "planeSpawnChance", "minItemsPerPlane",
                    "samePlaneYTolerance", "minItemSpacing", "lootDensity", "ammoRadiusMin", "ammoRadiusMax",
                    "companionAttachmentChance", "companionAttachmentKinds", "zoneSettings", "gunSelection", "operatorPermissions"), "general");
            General settings = new General(legacy ? Integer.MAX_VALUE : integer(g, "maxItemsPerPlane"),
                    number(g, "planeSpawnChance"), integer(g, legacy ? "minGroupsPerPlane" : "minItemsPerPlane"),
                    number(g, "samePlaneYTolerance"), number(g, legacy ? "minGroupSpacing" : "minItemSpacing"),
                    g.has("lootDensity") ? number(g, "lootDensity") : 0.1,
                    g.has("ammoRadiusMin") ? number(g, "ammoRadiusMin") : 1,
                    g.has("ammoRadiusMax") ? number(g, "ammoRadiusMax") : 1.5,
                    g.has("companionAttachmentChance") ? number(g, "companionAttachmentChance") : 0.5,
                    g.has("companionAttachmentKinds") ? integer(g, "companionAttachmentKinds") : 2,
                    g.has("zoneSettings") ? ZoneSettings.parse(g.get("zoneSettings")) : ZoneSettings.defaults(),
                    g.has("gunSelection") ? parseGunSelection(g.get("gunSelection")) : GunSelection.defaults(),
                    g.has("operatorPermissions") ? parseOperatorPermissions(g.get("operatorPermissions")) : OperatorPermissions.defaults());
            List<Entry> items = new ArrayList<>();
            if (legacy) {
                java.util.LinkedHashMap<String, Entry> merged = new java.util.LinkedHashMap<>();
                for (JsonElement element : array(required(root, "groups"), "groups")) {
                    JsonObject group = object(element, Texts.tr("message.tacz_pubg.loot_config_model.legacy_loot_group"));
                    keys(group, Set.of("id", "name", "enabled", "weight", "entries"), Texts.tr("message.tacz_pubg.loot_config_model.legacy_loot_group"));
                    double weight = bool(group, "enabled") ? number(group, "weight") : 0;
                    for (JsonElement value : array(required(group, "entries"), "entries")) {
                        JsonObject e = object(value, Texts.tr("message.tacz_pubg.loot_config_model.legacy_item"));
                        keys(e, Set.of("kind", "id", "minCount", "maxCount", "chance", "ammoForGun"), Texts.tr("message.tacz_pubg.loot_config_model.legacy_item"));
                        String kind = string(e, "kind"), id = string(e, "id");
                        double effective = "ammo".equals(kind) ? 0 : weight * (e.has("chance") ? number(e, "chance") : 1);
                        Entry next = new Entry(kind, id, integer(e, "minCount"), integer(e, "maxCount"), effective);
                        Entry previous = merged.get(next.key());
                        if (previous != null) next = new Entry(kind, id, Math.min(previous.minCount(), next.minCount()),
                                Math.max(previous.maxCount(), next.maxCount()), previous.weight() + next.weight());
                        merged.put(next.key(), next);
                    }
                }
                double maximum = merged.values().stream().mapToDouble(Entry::weight).max().orElse(0);
                // Keep relative probabilities when repeated legacy entries exceed the editor's per-row range.
                double divisor = maximum > 1_000_000 ? maximum / 1_000_000 : 1;
                for (Entry entry : merged.values()) items.add(new Entry(entry.kind(), entry.id(),
                        entry.minCount(), entry.maxCount(), entry.weight() / divisor));
            } else {
                for (JsonElement element : array(required(root, "items"), "items")) {
                    JsonObject e = object(element, Texts.tr("message.tacz_pubg.loot_config_model.item"));
                    keys(e, Set.of("kind", "id", "minCount", "maxCount", "weight", "ammoSupply"), Texts.tr("message.tacz_pubg.loot_config_model.item"));
                    items.add(new Entry(string(e, "kind"), string(e, "id"), integer(e, "minCount"),
                            integer(e, "maxCount"), number(e, "weight"),
                            !e.has("ammoSupply") || e.get("ammoSupply").isJsonNull() ? null
                                    : parseGunAmmoSupply(e.get("ammoSupply"))));
                }
            }
            GunSettings gunSettings;
            if (root.has("gunSettings")) {
                JsonObject choices = object(required(root, "gunSettings"), "gunSettings");
                keys(choices, Set.of("balance", "scopes", "ammo", "muzzle", "laser", "grip", "stock", "magazine", "usePubgOriginalIcons", "inventory", "lowPolyModels"), "gunSettings");
                gunSettings = new GunSettings(string(choices, "balance"), string(choices, "scopes"),
                        string(choices, "ammo"), string(choices, "muzzle"), string(choices, "laser"),
                        string(choices, "grip"), string(choices, "stock"), string(choices, "magazine"),
                        choices.has("usePubgOriginalIcons") && bool(choices, "usePubgOriginalIcons"),
                        com.example.examplemod.inventory.InventorySettings.parse(choices.get("inventory")),
                        choices.has("lowPolyModels") && bool(choices, "lowPolyModels"));
            } else gunSettings = GunSettings.fromLegacyProfile(root.has("gunBalanceProfile")
                    ? string(root, "gunBalanceProfile") : DEFAULT_GUN_PROFILE);
            Map<String, Double> categoryProbabilities = defaultCategoryProbabilities();
            boolean untouchedLegacyDefaults = false;
            if (root.has("categoryProbabilities")) {
                JsonObject categoryJson = object(required(root, "categoryProbabilities"), "categoryProbabilities");
                keys(categoryJson, LootCategory.IDS, "categoryProbabilities");
                Map<String, Double> parsed = new java.util.LinkedHashMap<>();
                for (String id : PubgLootDefaults.legacyCategories(categoryJson) ? categoryJson.keySet() : LootCategory.IDS)
                    parsed.put(id, number(categoryJson, id));
                untouchedLegacyDefaults = untouchedLegacyDefaults(categoryJson, items);
                categoryProbabilities = untouchedLegacyDefaults ? defaultCategoryProbabilities()
                        : PubgLootDefaults.legacyCategories(categoryJson) ? PubgLootDefaults.migrate(parsed, items) : parsed;
            }
            Map<String, TierProfile> profiles = inheritedTierProfiles(items, categoryProbabilities);
            if (untouchedLegacyDefaults) {
                profiles = new java.util.LinkedHashMap<>();
                for (LootTier tier : LootTier.values()) profiles.put(tier.id(),
                        new TierProfile(items, PubgLootDefaults.profile(tier).categoryProbabilities()));
            }
            if (root.has("tierProfiles")) {
                JsonObject tiers = object(root.get("tierProfiles"), "tierProfiles");
                Set<String> ids = java.util.Arrays.stream(LootTier.values()).map(LootTier::id).collect(java.util.stream.Collectors.toSet());
                if (!tiers.keySet().equals(ids)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.all_five_loot_tiers_required_very_low_low"));
                profiles = new java.util.LinkedHashMap<>();
                for (LootTier tier : LootTier.values()) {
                    JsonObject profile = object(tiers.get(tier.id()), tier.label());
                    keys(profile, Set.of("items", "categoryProbabilities"), tier.label());
                    JsonObject nested = root.deepCopy(); nested.remove("tierProfiles"); nested.remove("groups");
                    nested.add("items", required(profile, "items"));
                    nested.add("categoryProbabilities", required(profile, "categoryProbabilities"));
                    var parsed = parse(nested.toString());
                    var originalCategories = profile.getAsJsonObject("categoryProbabilities");
                    profiles.put(tier.id(), new TierProfile(parsed.items(),
                            untouchedLegacyDefaults(originalCategories, parsed.items()) ? PubgLootDefaults.profile(tier).categoryProbabilities()
                                    : parsed.categoryProbabilities()));
                }
            }
            Document document = new Document(settings, items, gunSettings, categoryProbabilities, profiles);
            List<String> errors = validate(document);
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
            return document;
        } catch (IllegalArgumentException error) { throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.invalid_configuration_json", error.getMessage()), error);
        }
    }

    /** A default category map may still contain an author's custom ordinary-item pool. */
    private static boolean untouchedLegacyDefaults(JsonObject categories, List<Entry> items) {
        return PubgLootDefaults.legacyDefaults(categories)
                && items.stream().noneMatch(entry -> "item".equals(entry.kind()) && entry.weight() > 0);
    }

    public static List<String> validate(Document document) {
        List<String> errors = new ArrayList<>();
        if (document == null || document.general() == null) return List.of(Texts.tr("message.tacz_pubg.loot_config_model.general_configuration_required"));
        for (GunSettingCategory category : GunSettingCategory.values()) {
            String choice = document.gunSettings().value(category);
            if (!INITIAL.equals(choice) && !EXPERIMENTAL.equals(choice))
                errors.add(category.label() + Texts.tr("message.tacz_pubg.loot_config_model.initial_experimental"));
        }
        General g = document.general();
        errors.addAll(g.gunSelection().validate());
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.maximum_items_per_surface"), g.maxItemsPerPlane(), 0, Integer.MAX_VALUE);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.minimum_items_per_surface"), g.minItemsPerPlane(), 0, Integer.MAX_VALUE);
        if (g.minItemsPerPlane() > g.maxItemsPerPlane()) errors.add(Texts.tr("message.tacz_pubg.loot_config_model.set_minimum_count_no_greater_than_maximum"));
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.surface_spawn_probability"), g.planeSpawnChance(), 0, 1);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.surface_height_tolerance"), g.samePlaneYTolerance(), 0, 2);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.minimum_item_spacing"), g.minItemSpacing(), 0, 64);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.loot_density_items_m"), g.lootDensity(), 0, 1000);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.minimum_ammo_radius"), g.ammoRadiusMin(), 0, 64);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.maximum_ammo_radius"), g.ammoRadiusMax(), 0, 64);
        if (g.ammoRadiusMin() > g.ammoRadiusMax()) errors.add(Texts.tr("message.tacz_pubg.loot_config_model.set_minimum_ammo_radius_no_greater_than_maximum"));
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.companion_dedicated_attachment_probability"), g.companionAttachmentChance(), 0, 1);
        range(errors, Texts.tr("message.tacz_pubg.loot_config_model.companion_dedicated_attachment_types_b"), g.companionAttachmentKinds(), 0, 16);
        validatePool(document.items(), document.categoryProbabilities(), errors);
        if (!document.tierProfiles().isEmpty()) {
            Set<String> tierIds = java.util.Arrays.stream(LootTier.values()).map(LootTier::id).collect(java.util.stream.Collectors.toSet());
            if (!document.tierProfiles().keySet().equals(tierIds)) errors.add(Texts.tr("message.tacz_pubg.loot_config_model.all_five_loot_tiers_required"));
            for (var tier : LootTier.values()) {
                var profile = document.tierProfiles().get(tier.id());
                if (profile == null) { errors.add(tier.label() + Texts.tr("message.tacz_pubg.loot_config_model.tier_configuration_missing")); continue; }
                // An inherited identical pool has already been checked above; keep one actionable error.
                if (profile.items().equals(document.items()) && profile.categoryProbabilities().equals(document.categoryProbabilities())) continue;
                var profileErrors = new ArrayList<String>();
                validatePool(profile.items(), profile.categoryProbabilities(), profileErrors);
                for (String error : profileErrors) errors.add(error + "（" + tier.label() + Texts.tr("message.tacz_pubg.loot_config_model.tier"));
            }
        }
        return List.copyOf(errors);
    }

    private static void validatePool(List<Entry> entries, Map<String, Double> probabilities, List<String> errors) {
        validateCategoryProbabilities(probabilities, errors);
        Set<String> ids = new HashSet<>();
        for (Entry e : entries) {
            String label = Texts.tr("message.tacz_pubg.loot_config_model.item.validate_pool", e.id());
            if (e.kind() == null || !KINDS.contains(e.kind())) errors.add(label + Texts.tr("message.tacz_pubg.loot_config_model.category_gun_ammo_attachment_item"));
            if (!isResourceId(e.id())) errors.add(label + Texts.tr("message.tacz_pubg.loot_config_model.enter_valid_namespace_path_id_characters"));
            if (!ids.add(e.key())) errors.add(label + Texts.tr("message.tacz_pubg.loot_config_model.duplicate_entries_merged_into_one_item_configuration"));
            int limit = "gun".equals(e.kind()) ? 1 : 4096;
            if (e.minCount() < 1 || e.maxCount() < e.minCount() || e.maxCount() > limit)
                errors.add(label + Texts.tr("message.tacz_pubg.loot_config_model.quantity_satisfy_minimum_maximum") + limit);
            range(errors, label + Texts.tr("message.tacz_pubg.loot_config_model.relative_weight"), e.weight(), 0, 1_000_000);
            if (e.ammoSupply() != null) {
                if (!"gun".equals(e.kind())) errors.add(label + Texts.tr("message.tacz_pubg.loot_catalog.per_gun_ammo_supplies_only_allowed_gun_entries"));
                for (String error : validateGunAmmoSupply(e.ammoSupply())) errors.add(label + "：" + error);
            }
        }
    }

    private static OperatorPermissions parseOperatorPermissions(JsonElement element) {
        JsonObject permissions = object(element, Texts.tr("message.tacz_pubg.loot_config_model.op_permissions"));
        Set<String> fields = java.util.Arrays.stream(OperatorPermissions.Permission.values())
                .map(OperatorPermissions.Permission::field).collect(java.util.stream.Collectors.toSet());
        keys(permissions, fields, Texts.tr("message.tacz_pubg.loot_config_model.op_permissions"));
        OperatorPermissions result = OperatorPermissions.defaults();
        for (OperatorPermissions.Permission permission : OperatorPermissions.Permission.values())
            result = result.with(permission, bool(permissions, permission.field()));
        return result;
    }

    private static GunSelection parseGunSelection(JsonElement element) {
        JsonObject selection = object(element, Texts.tr("message.tacz_pubg.loot_config_model.gun_class_selection"));
        keys(selection, Set.of("probabilities"), Texts.tr("message.tacz_pubg.loot_config_model.gun_class_selection"));
        JsonElement values = selection.get("probabilities");
        // Gson omits null fields, so both an empty object and an explicit null mean count defaults.
        if (values == null || values.isJsonNull()) return GunSelection.defaults();
        JsonObject probabilities = object(values, Texts.tr("message.tacz_pubg.loot_config_model.gun_class_probability"));
        if (!probabilities.keySet().equals(GunClass.IDS)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.gun_selection.all_nine_gun_classes_required"));
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        for (GunClass type : GunClass.values()) result.put(type.id(), number(probabilities, type.id()));
        return new GunSelection(result);
    }

    private static GunAmmoSupply parseGunAmmoSupply(JsonElement element) {
        JsonObject supply = object(element, "ammoSupply");
        keys(supply, Set.of("ammoOptions", "roundsPerGroup"), "ammoSupply");
        List<AmmoOption> options = new ArrayList<>();
        for (JsonElement value : array(required(supply, "ammoOptions"), "ammoOptions")) {
            JsonObject option = object(value, Texts.tr("message.tacz_pubg.loot_config_model.ammo_option"));
            keys(option, Set.of("ammoId", "groups", "probability"), Texts.tr("message.tacz_pubg.loot_config_model.ammo_loadout"));
            options.add(new AmmoOption(string(option, "ammoId"), integer(option, "groups"), number(option, "probability")));
        }
        return new GunAmmoSupply(options, integer(supply, "roundsPerGroup"));
    }

    /** Structural validation only; server catalog validation additionally resolves all ammo assets. */
    public static List<String> validateGunAmmoSupply(GunAmmoSupply supply) {
        List<String> errors = new ArrayList<>();
        if (supply == null) return List.of(Texts.tr("message.tacz_pubg.loot_config_model.per_gun_ammo_configuration_required"));
        if (supply.ammoOptions().size() != AMMO_SUPPLY_CHOICES) errors.add(Texts.tr("message.tacz_pubg.loot_config_model.exactly_three_ammo_options_required"));
        double ammoTotal = 0;
        boolean ammoValid = true;
        for (AmmoOption option : supply.ammoOptions()) {
            if (option == null) { errors.add(Texts.tr("message.tacz_pubg.loot_config_model.ammo_option_required")); ammoValid = false; continue; }
            if (!SELF_AMMO.equals(option.ammoId()) && !isResourceId(option.ammoId()))
                errors.add(Texts.tr("message.tacz_pubg.loot_config_model.ammo_id_self_full_namespace_path"));
            range(errors, Texts.tr("screen.tacz_pubg.loot_config.loadout_groups"), option.groups(), 1, MAX_AMMO_GROUPS);
            double probability = option.probability();
            if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                errors.add(Texts.tr("message.tacz_pubg.loot_config_model.ammo_option_probability_finite_between")); ammoValid = false;
            } else ammoTotal += probability;
        }
        if (ammoValid && Math.abs(ammoTotal - 1) > AMMO_PROBABILITY_TOLERANCE)
            errors.add(Texts.tr("message.tacz_pubg.loot_config_model.ammo_option_probabilities_total"));
        range(errors, Texts.tr("screen.tacz_pubg.gun_ammo_supply.rounds_per_group"), supply.roundsPerGroup(), 1, MAX_AMMO_ROUNDS_PER_GROUP);
        return List.copyOf(errors);
    }

    private static void validateCategoryProbabilities(Map<String, Double> probabilities, List<String> errors) {
        if (probabilities == null) { errors.add(Texts.tr("message.tacz_pubg.loot_config_model.category_probability_configuration_required")); return; }
        boolean valid = true;
        for (String key : probabilities.keySet()) {
            if (key == null || !LootCategory.IDS.contains(key)) { errors.add(Texts.tr("message.tacz_pubg.loot_config_model.check_probability_category", key)); valid = false; }
        }
        for (LootCategory category : LootCategory.values()) {
            Double probability = probabilities.get(category.id());
            if (probability == null) {
                errors.add(Texts.tr("message.tacz_pubg.loot_config_model.add_missing_probability_category", category.id())); valid = false;
            } else if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                errors.add(category.label() + Texts.tr("message.tacz_pubg.loot_config_model.category_probability_finite_between")); valid = false;
            }
        }
        if (valid) {
            double sum = categoryProbabilityTotal(probabilities);
            if (Math.abs(sum - 1) > CATEGORY_TOTAL_TOLERANCE)
                errors.add(CATEGORY_TOTAL_ERROR_PREFIX + Texts.tr("message.tacz_pubg.loot_config_model.total_currently") + String.format(java.util.Locale.ROOT, "%.8f", sum * 100) + "%）");
        }
    }

    /** Gson streaming reader in strict mode, with duplicate-key and nesting checks. */
    static JsonElement readJson(String json) {
        return readJson(json, MAX_JSON_BYTES);
    }

    public static JsonElement readJson(String json, int byteLimit) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > byteLimit) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.configuration_json_exceeds_transfer_byte_budget"));
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement result = readElement(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.unexpected_content_after_json"));
            return result;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.invalid_configuration_json", error.getMessage()), error);
        }
    }

    private static JsonElement readElement(JsonReader reader, int depth) throws IOException {
        if (depth > 12) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.json_nesting_too_deep"));
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.duplicate_json_field", key));
                    object.add(key, readElement(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readElement(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.check_json_value", reader.getPath()));
        };
    }

    private static JsonObject object(JsonElement element, String label) {
        if (!element.isJsonObject()) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.loot_config_model.object"));
        return element.getAsJsonObject();
    }

    private static boolean isResourceId(String value) {
        if (value == null || value.length() > 128) return false;
        int separator = value.indexOf(':');
        return separator > 0 && separator < value.length() - 1 && ResourceLocation.tryParse(value) != null;
    }

    private static JsonArray array(JsonElement element, String label) {
        if (!element.isJsonArray()) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.loot_config_model.array"));
        return element.getAsJsonArray();
    }

    private static void keys(JsonObject object, Set<String> allowed, String label) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + Texts.tr("message.tacz_pubg.loot_config_model.check_field") + key);
        }
    }

    private static JsonElement required(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_config_model.add_missing_field", key));
        return object.get(key);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.string"));
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.true_false"));
        }
        return value.getAsBoolean();
    }

    private static double number(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.number"));
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.enter_finite_number"));
        return result;
    }

    private static int integer(JsonObject object, String key) {
        number(object, key);
        try {
            return object.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(key + Texts.tr("message.tacz_pubg.loot_config_model.integer"));
        }
    }

    private static void range(List<String> errors, String label, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            errors.add(label + Texts.tr("message.tacz_pubg.loot_config_model.between") + min + "–" + max + Texts.tr("message.tacz_pubg.loot_config_model.inclusive"));
        }
    }
}
