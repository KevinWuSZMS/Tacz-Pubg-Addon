package com.example.examplemod.lootconfig;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.ExampleMod;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;
import com.google.gson.JsonParser;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.example.examplemod.zones.heavy.HeavyWeaponInfo;
import com.example.examplemod.zones.heavy.HeavyWeaponCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Server-side source of selectable loot. Asset IDs and item registry IDs are
 * intentionally different namespaces of choices: many TaCZ guns share one Item.
 * Call only after the server's gun-pack resources have finished loading.
 */
public final class LootCatalog {
    private static final int MAX_GUN_COUNT = 1;
    private static final int MAX_OTHER_COUNT = 4_096;
    private static final Set<String> DMR_GUN_IDS = loadDmrGunIds();
    private static final Set<String> CLASS_DMR_IDS = loadClassDmrIds();

    private LootCatalog() {
    }

    /** The client translates the preview's hover name in its current language. */
    public record Choice(String kind, String id, ItemStack preview, AttachmentLootPolicy.Info attachmentInfo,
            @Nullable GunAmmoSupply ammoDefaults, boolean bombingAmmo, String gunClass, @Nullable HeavyWeaponInfo heavyWeapon) {
        public Choice(String kind, String id, ItemStack preview, AttachmentLootPolicy.Info attachmentInfo,
                @Nullable GunAmmoSupply ammoDefaults, boolean bombingAmmo) {
            this(kind, id, preview, attachmentInfo, ammoDefaults, bombingAmmo, GunClass.OTHER.id(), null);
        }
        public Choice(String kind, String id, ItemStack preview, AttachmentLootPolicy.Info attachmentInfo,
                @Nullable GunAmmoSupply ammoDefaults) {
            this(kind, id, preview, attachmentInfo, ammoDefaults, false);
        }
        public Choice(String kind, String id, ItemStack preview) {
            this(kind, id, preview, new AttachmentLootPolicy.Info("other", 0, false), null);
        }
        public Choice(String kind, String id, ItemStack preview, AttachmentLootPolicy.Info attachmentInfo) {
            this(kind, id, preview, attachmentInfo, null);
        }
        public Choice {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
            Objects.requireNonNull(preview);
            Objects.requireNonNull(attachmentInfo);
            GunClass.byId(gunClass);
            if (heavyWeapon != null && (!"gun".equals(kind) || !GunClass.HEAVY.id().equals(gunClass)))
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.heavy_projectile_data_only_allowed_heavy_weapon_catalog"));
            if (bombingAmmo && !"ammo".equals(kind)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.bombardment_ammo_marker_only_allowed_ammo_entries"));
            if (ammoDefaults != null) {
                if (!"gun".equals(kind)) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.per_gun_defaults_only_allowed_gun_entries"));
                List<String> errors = LootConfigModel.validateGunAmmoSupply(ammoDefaults);
                if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
            }
            if (preview.isEmpty()) {
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.catalog_item_preview_required", kind, id));
            }
            preview = preview.copyWithCount(1);
        }
    }

    public static List<Choice> build(ServerLevel level) {
        Objects.requireNonNull(level);
        List<Choice> result = new ArrayList<>();
        for (ResourceLocation id : gunIds()) {
            addIfBuildable(result, "gun", id, level);
        }
        TimelessAPI.getAllCommonAmmoIndex().stream()
                .map(Map.Entry::getKey).sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(id -> addIfBuildable(result, "ammo", id, level));
        TimelessAPI.getAllCommonAttachmentIndex().stream()
                .map(Map.Entry::getKey).sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(id -> addIfBuildable(result, "attachment", id, level));

        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList()) {
            Item item = BuiltInRegistries.ITEM.get(id);
            // TaCZ registry items are generic asset shells or authoring/manufacturing helpers.
            // Its actual guns, loose ammo and attachments are listed by their asset IDs above.
            if (item == null || item == Items.AIR || isAssetBacked(item)
                    || "tacz".equals(id.getNamespace()) || !item.isEnabled(level.enabledFeatures())) {
                continue;
            }
            addIfBuildable(result, "item", id, level);
        }
        AttachmentLootPolicy.Snapshot attachmentPolicy = AttachmentLootPolicy.captureSnapshot();
        Set<String> bombingAmmoIds = com.example.examplemod.zones.bombing.BombingAmmoCatalog.ids();
        Map<String, GunClass> classes = gunClasses();
        Map<String, HeavyWeaponInfo> heavy = HeavyWeaponCatalog.capture();
        return result.stream().map(choice -> {
            var info = attachmentPolicy.info(choice.id());
            return new Choice(choice.kind(), choice.id(), choice.preview(), info == null ? choice.attachmentInfo() : info,
                    choice.ammoDefaults(), choice.kind().equals("ammo") && bombingAmmoIds.contains(choice.id()),
                    classes.getOrDefault(choice.id(), GunClass.OTHER).id(), "gun".equals(choice.kind()) ? heavy.get(choice.id()) : null);
        }).toList();
    }

    /** Null means valid; count is a total quantity and may require multiple stacks. */
    @Nullable
    public static String validationError(Entry entry, ServerLevel level) {
        if (entry == null) {
            return Texts.tr("message.tacz_pubg.loot_catalog.loot_entry_required");
        }
        if (entry.kind() == null || !List.of("gun", "ammo", "attachment", "item").contains(entry.kind())) {
            return Texts.tr("message.tacz_pubg.loot_catalog.type_gun_ammo_attachment_item");
        }
        ResourceLocation id = parseId(entry.id());
        if (id == null) {
            return Texts.tr("message.tacz_pubg.loot_catalog.loot_ids_lowercase_namespace_path", entry.id());
        }
        int maximum = "gun".equals(entry.kind()) ? MAX_GUN_COUNT : MAX_OTHER_COUNT;
        if (entry.minCount() < 1 || entry.maxCount() < entry.minCount() || entry.maxCount() > maximum) {
            return Texts.tr("message.tacz_pubg.loot_catalog.quantity_satisfy_minimum_maximum", maximum);
        }
        if (!Double.isFinite(entry.weight()) || entry.weight() < 0 || entry.weight() > 1_000_000) {
            return Texts.tr("message.tacz_pubg.loot_catalog.relative_weight");
        }
        if (entry.ammoSupply() != null) {
            if (!"gun".equals(entry.kind())) return Texts.tr("message.tacz_pubg.loot_catalog.per_gun_ammo_supplies_only_allowed_gun_entries");
            List<String> errors = LootConfigModel.validateGunAmmoSupply(entry.ammoSupply());
            if (!errors.isEmpty()) return String.join("；", errors);
        }
        try {
            String idError = assetError(entry.kind(), id, level);
            if (idError != null) {
                return idError;
            }
            if ("gun".equals(entry.kind())) {
                GunAmmoSupply supply = entry.ammoSupply() == null ? gunAmmoDefaults(id) : entry.ammoSupply();
                String ammoError = ammoSupplyValidationError(supply, ammoForGun(id),
                        ammo -> TimelessAPI.getCommonAmmoIndex(ammo).isPresent());
                if (ammoError != null) return ammoError;
            }
            if (preview(entry.kind(), id, level).isEmpty()) {
                return Texts.tr("message.tacz_pubg.loot_catalog.check_resource_constructor_arguments", entry.kind(), id);
            }
            return null;
        } catch (RuntimeException exception) {
            ExampleMod.LOGGER.warn("Cannot validate loot asset {} {}", entry.kind(), id, exception);
            return Texts.tr("message.tacz_pubg.loot_catalog.failed_read_loot_see_server_log", entry.kind(), id);
        }
    }

    /** Include the mod's usable equipment, consumables and throwables, never crafting helpers. */
    public static List<Entry> defaults(ServerLevel level) {
        return defaults(build(level), LootTier.HIGH);
    }

    private static List<Entry> defaults(List<Choice> catalog, LootTier tier) {
        var own = PubgLootDefaults.profile(tier).items().stream()
                .collect(java.util.stream.Collectors.toMap(Entry::id, entry -> entry));
        return catalog.stream().filter(choice -> !"item".equals(choice.kind()) || own.containsKey(choice.id()))
                .map(choice -> "item".equals(choice.kind()) ? own.get(choice.id()) : new Entry(choice.kind(), choice.id(),
                        "ammo".equals(choice.kind()) ? 30 : 1, "ammo".equals(choice.kind()) ? 30 : 1,
                        "gun".equals(choice.kind()) || "attachment".equals(choice.kind()) ? 1 : 0)).toList();
    }

    public static LootConfigModel.Document defaultDocument(ServerLevel level) {
        var catalog = build(level);
        var profiles = new java.util.LinkedHashMap<String, LootConfigModel.TierProfile>();
        for (LootTier tier : LootTier.values()) profiles.put(tier.id(),
                new LootConfigModel.TierProfile(defaults(catalog, tier), PubgLootDefaults.profile(tier).categoryProbabilities()));
        var high = profiles.get(LootTier.HIGH.id());
        return new LootConfigModel.Document(LootConfigModel.General.defaults(), high.items(),
                LootConfigModel.GunSettings.defaults(), high.categoryProbabilities(), profiles);
    }

    /** New assets become editable without changing an author's existing weights. */
    public static LootConfigModel.Document supplement(LootConfigModel.Document document, ServerLevel level) {
        var catalog = build(level);
        java.util.LinkedHashMap<String, Entry> merged = new java.util.LinkedHashMap<>();
        document.items().forEach(entry -> merged.put(entry.key(), entry));
        defaults(catalog, LootTier.HIGH).forEach(entry -> merged.putIfAbsent(entry.key(), entry));
        var profiles = new java.util.LinkedHashMap<String, LootConfigModel.TierProfile>();
        for (LootTier tier : LootTier.values()) {
            var profile = document.tier(tier);
            var entries = new java.util.LinkedHashMap<String, Entry>();
            profile.items().forEach(entry -> entries.put(entry.key(), entry));
            defaults(catalog, tier).forEach(entry -> entries.putIfAbsent(entry.key(), entry));
            profiles.put(tier.id(), new LootConfigModel.TierProfile(List.copyOf(entries.values()), profile.categoryProbabilities()));
        }
        return new LootConfigModel.Document(document.general(), List.copyOf(merged.values()), document.gunSettings(),
                document.categoryProbabilities(), profiles);
    }

    /** Returns only an ammo ID which really exists in the server's loaded packs. */
    @Nullable
    public static ResourceLocation ammoForGun(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .filter(id -> TimelessAPI.getCommonAmmoIndex(id).isPresent())
                .orElse(null);
    }

    @Nullable
    public static ResourceLocation ammoForGun(ItemStack gun) {
        if (gun == null || gun.isEmpty() || !(gun.getItem() instanceof IGun access)) return null;
        return ammoForGun(access.getGunId(gun));
    }

    /** Dynamic defaults follow the currently applied server ammo ID; they do not alter gun chambering. */
    public static GunAmmoSupply gunAmmoDefaults(ResourceLocation gunId) {
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(Objects.requireNonNull(gunId))
                .orElseThrow(() -> new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.load_gun_asset", gunId)));
        ResourceLocation ammoId = index.getGunData().getAmmoId();
        return defaultGunAmmoSupply(index.getType(), gunId.toString(), ammoId == null ? "" : ammoId.toString());
    }

    static GunAmmoSupply defaultGunAmmoSupply(String gunType, String gunId, String ammoId) {
        int rounds = defaultGunAmmoRounds(gunType, gunId, ammoId);
        String type = gunType == null ? "" : gunType.toLowerCase(Locale.ROOT);
        boolean rifleFamily = rounds == 30 && !DMR_GUN_IDS.contains(gunId == null ? "" : gunId.toLowerCase(Locale.ROOT))
                && Set.of("rifle", "ar", "assault_rifle", "smg", "mg", "lmg", "machine_gun", "machinegun").contains(type);
        return GunAmmoSupply.defaults(rounds, rifleFamily);
    }

    /** Shared asset check with injectable lookup, so offline checks exercise the actual server rule. */
    @Nullable
    static String ammoSupplyValidationError(GunAmmoSupply supply, @Nullable ResourceLocation selfAmmo,
            java.util.function.Predicate<ResourceLocation> exists) {
        List<String> errors = LootConfigModel.validateGunAmmoSupply(supply);
        if (!errors.isEmpty()) return String.join("；", errors);
        for (LootConfigModel.AmmoOption option : supply.ammoOptions()) {
            ResourceLocation ammo = LootConfigModel.SELF_AMMO.equals(option.ammoId())
                    ? selfAmmo : parseId(option.ammoId());
            // Probability zero still describes a selected asset, and must not conceal a stale ID.
            if (ammo == null || !exists.test(ammo)) return Texts.tr("message.tacz_pubg.loot_catalog.load_companion_ammo_asset", option.ammoId());
        }
        return null;
    }

    /** Pure classification shared by the dynamic lookup and deterministic regression checks. */
    static int defaultGunAmmoRounds(String gunType, String gunId, String ammoId) {
        String type = gunType == null ? "" : gunType.toLowerCase(Locale.ROOT);
        String gun = gunId == null ? "" : gunId.toLowerCase(Locale.ROOT);
        String ammo = ammoId == null ? "" : ammoId.toLowerCase(Locale.ROOT);
        String gunPath = gun.substring(gun.indexOf(':') + 1);
        String ammoPath = ammo.substring(ammo.indexOf(':') + 1);
        // TaCZ r7 labels M320 and all current QLU11/MGL/M203/M79 launchers as rpg too.
        // Their 40mm/35mm/35x32mm grenades must win over the generic rpg fallback.
        if (Set.of("gl", "grenade", "grenade_launcher").contains(type)
                || Set.of("40mm", "35mm", "35x32mm").contains(ammoPath)
                || ammoPath.contains("grenade")
                || Set.of("m320", "m203", "m79", "mgl_40mm", "qlu11").contains(gunPath)) return 3;
        if (DMR_GUN_IDS.contains(gun) || "dmr".equals(type)) return 15;
        return switch (type) {
            case "rpg", "rocket", "rocket_launcher" -> 1;
            case "sg", "shotgun" -> 10;
            case "sniper", "sr", "pistol", "handgun" -> 15;
            default -> 30; // rifle / AR, SMG, MG / LMG, and editable fallback for unknown pack types.
        };
    }

    private static Set<String> loadDmrGunIds() {
        // Roles are classification evidence even while the optional balance experiment is disabled.
        try (var stream = LootCatalog.class.getResourceAsStream("/assets/tacz_pubg/approved_role_balance.json")) {
            if (stream == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.loot_catalog.missing_approved_gun_role_catalog"));
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> ids = new java.util.HashSet<>();
            for (var element : root.getAsJsonArray("guns")) {
                var gun = element.getAsJsonObject();
                if ("dmr".equals(gun.get("role").getAsString())) ids.add(gun.get("gunId").getAsString());
            }
            return Set.copyOf(ids);
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Cannot load DMR ammo defaults; native gun types remain available", error);
            return Set.of();
        }
    }

    private static Set<String> loadClassDmrIds() {
        Set<String> ids = new java.util.HashSet<>(DMR_GUN_IDS);
        try (var stream = LootCatalog.class.getResourceAsStream("/data/tacz_pubg/gun_balance/pubg_magazine_policy.json")) {
            if (stream != null) for (var element : JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("guns")) {
                var gun = element.getAsJsonObject();
                if ("DMR".equals(gun.get("category").getAsString())) ids.add(gun.get("gunId").getAsString());
            }
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Cannot load gun class supplement", error);
        }
        return Set.copyOf(ids);
    }

    /** One immutable full-directory snapshot, independent of enabled loot entry weights. */
    public static Map<String, GunClass> gunClasses() {
        Map<String, GunClass> result = new java.util.TreeMap<>();
        TimelessAPI.getAllCommonGunIndex().forEach(entry -> result.put(entry.getKey().toString(),
                GunClass.classify(entry.getValue().getType(), CLASS_DMR_IDS.contains(entry.getKey().toString()))));
        return Map.copyOf(result);
    }

    public static Map<GunClass, Integer> gunClassCounts(Map<String, GunClass> classes) {
        Map<GunClass, Integer> counts = new java.util.EnumMap<>(GunClass.class);
        classes.values().forEach(value -> counts.merge(value, 1, Integer::sum));
        return Map.copyOf(counts);
    }

    /**
     * TaCZ r7 base magazine capacity; installed attachments are deliberately ignored.
     * It excludes any extra chambered round and never uses the ammo item's stack limit.
     */
    public static int magazineCapacity(ItemStack gun) {
        if (gun == null || gun.isEmpty() || !(gun.getItem() instanceof IGun access)) {
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.provide_actual_tacz_gun_item_read_magazine_capacity"));
        }
        ResourceLocation id = access.getGunId(gun);
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(id)
                .orElseThrow(() -> new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.load_gun_asset", id)));
        int capacity = index.getGunData().getAmmoAmount();
        if (capacity < 1) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.check_gun_s_magazine_capacity", id, capacity));
        return capacity;
    }

    /** Unloaded gun preview, with a firing mode declared by its own gun data. */
    public static ItemStack gunPreview(ResourceLocation gunId, ServerLevel level) {
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null || index.getGunData() == null) {
            return ItemStack.EMPTY;
        }
        FireMode mode = index.getGunData().getFireModeSet().stream()
                .filter(value -> value != null && value != FireMode.UNKNOWN)
                .findFirst().orElse(null);
        if (mode == null) {
            return ItemStack.EMPTY;
        }
        return GunItemBuilder.create().setId(gunId).setFireMode(mode)
                .setAmmoCount(0).setAmmoInBarrel(false).build(level.registryAccess());
    }

    /**
     * Builds the selected asset independently of optional companion ammunition.
     * Authoring validation still checks the complete configuration; a missing
     * companion resource during generation must not prevent the gun itself.
     */
    public static ItemStack createStack(String kind, String id, ServerLevel level) {
        ResourceLocation resource = parseId(id);
        if (resource == null)
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.loot_ids_lowercase_namespace_path", id));
        String error = assetError(kind, resource, level);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        ItemStack stack = preview(kind, resource, level);
        if (stack.isEmpty())
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_catalog.check_resource_constructor_arguments", kind, id));
        return stack.copy();
    }

    private static List<ResourceLocation> gunIds() {
        return TimelessAPI.getAllCommonGunIndex().stream().map(Map.Entry::getKey)
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
    }

    private static void addIfBuildable(List<Choice> result, String kind, ResourceLocation id, ServerLevel level) {
        ItemStack stack;
        try {
            stack = preview(kind, id, level);
        } catch (RuntimeException exception) {
            ExampleMod.LOGGER.warn("Skipping invalid loot preview {} {}", kind, id, exception);
            return;
        }
        if (stack.isEmpty()) {
            ExampleMod.LOGGER.warn("Skipping empty loot preview {} {}", kind, id);
            return;
        }
        result.add(new Choice(kind, id.toString(), stack, new AttachmentLootPolicy.Info("other", 0, false),
                "gun".equals(kind) ? gunAmmoDefaults(id) : null));
    }

    private static ItemStack preview(String kind, ResourceLocation id, ServerLevel level) {
        return switch (kind) {
            case "gun" -> gunPreview(id, level);
            case "ammo" -> TimelessAPI.getCommonAmmoIndex(id).isPresent()
                    ? AmmoItemBuilder.create().setId(id).setCount(1).build() : ItemStack.EMPTY;
            case "attachment" -> TimelessAPI.getCommonAttachmentIndex(id).isPresent()
                    ? AttachmentItemBuilder.create().setId(id).setCount(1).build() : ItemStack.EMPTY;
            case "item" -> BuiltInRegistries.ITEM.containsKey(id)
                    ? new ItemStack(BuiltInRegistries.ITEM.get(id)) : ItemStack.EMPTY;
            default -> ItemStack.EMPTY;
        };
    }

    @Nullable
    private static String assetError(String kind, ResourceLocation id, ServerLevel level) {
        return switch (kind) {
            case "gun" -> TimelessAPI.getCommonGunIndex(id).isPresent() ? null : Texts.tr("message.tacz_pubg.loot_catalog.load_gun_asset", id);
            case "ammo" -> TimelessAPI.getCommonAmmoIndex(id).isPresent() ? null : Texts.tr("message.tacz_pubg.loot_catalog.load_corresponding_ammo_asset", id);
            case "attachment" -> TimelessAPI.getCommonAttachmentIndex(id).isPresent() ? null : Texts.tr("message.tacz_pubg.loot_catalog.load_corresponding_attachment_asset", id);
            case "item" -> itemError(id, level);
            default -> Texts.tr("message.tacz_pubg.loot_catalog.select_valid_loot_type", kind);
        };
    }

    @Nullable
    private static String itemError(ResourceLocation id, ServerLevel level) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return Texts.tr("message.tacz_pubg.loot_catalog.select_registered_item_id", id);
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return Texts.tr("message.tacz_pubg.loot_catalog.select_valid_loot_item");
        }
        if (isAssetBacked(item)) {
            return Texts.tr("message.tacz_pubg.loot_catalog.tacz_guns_ammo_attachments_enter_actual_asset_id", id);
        }
        return item.isEnabled(level.enabledFeatures()) ? null : Texts.tr("message.tacz_pubg.loot_catalog.enable_world_feature_required_by_item", id);
    }

    private static boolean isAssetBacked(Item item) {
        boolean taczAsset = item instanceof IGun || item instanceof IAmmo || item instanceof IAttachment;
        if (taczAsset) return true;
        if (!(item instanceof me.xjqsh.lrtactical.api.item.IThrowable throwable)) return false;
        // IThrowable also describes ordinary registered items that bind their LR animation/effect
        // ID themselves. PUBG's seven items need no per-stack ThrowableId to be usable; only LR's
        // generic shell has an empty default identity and must stay out of the ordinary item pool.
        return requiresAssetIdentity(false, true, throwable.getId(item.getDefaultInstance()));
    }

    /** Same classification used by listing and save validation, without bootstrapping item registries. */
    static boolean requiresAssetIdentity(boolean taczAsset, boolean throwable, @Nullable ResourceLocation defaultThrowableId) {
        return taczAsset || throwable && (defaultThrowableId == null
                || defaultThrowableId.equals(me.xjqsh.lrtactical.api.item.IThrowable.EMPTY));
    }

    @Nullable
    private static ResourceLocation parseId(@Nullable String value) {
        if (value == null || value.length() > 128 || value.indexOf(':') <= 0) {
            return null;
        }
        ResourceLocation result = ResourceLocation.tryParse(value);
        return result != null && result.toString().equals(value) ? result : null;
    }
}
