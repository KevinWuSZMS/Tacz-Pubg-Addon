package com.example.examplemod.lootconfig;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.examplemod.gunbalance.PubgMagazineRules;
import com.example.examplemod.gunbalance.PubgScopeRules;
import com.example.examplemod.gunbalance.ammo.AmmoProfiles;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettingCategory;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import com.tacz.guns.api.item.IGun;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Effective generation weights; the author's base entries are never overwritten. */
public final class AttachmentLootPolicy {
    public static final double SPECIALIST_WEIGHT_CAP = 0.01;
    public static final Set<String> SEMANTIC_GRIP_LASERS = Set.of("cbrg:laser_sight", "suffuse:pistollaser");
    public static final Set<String> APPROVED_LASERS = Set.of("cbrg:laser_sight");
    public static final Set<String> APPROVED_SCOPES = PubgScopeRules.SCOPE_IDS;
    public static final Set<String> APPROVED_MAGAZINES = PubgMagazineRules.MAGAZINE_IDS;
    public static final Set<String> APPROVED_AMMO = AmmoProfiles.targetAmmoIds().stream()
            .map(Object::toString).collect(Collectors.toUnmodifiableSet());

    /** category is semantic: scope/magazine/laser/stock/muzzle/grip/ammo/other. */
    public record Info(String category, int supportedGunCount, boolean specialist) {}

    public record Snapshot(int loadedGunCount, Map<String, Info> infos,
                           Map<String, Set<String>> supportingGunIds, Map<String, Set<String>> approvedIds) {
        public Snapshot {
            infos = Map.copyOf(infos);
            Map<String, Set<String>> support = new HashMap<>();
            supportingGunIds.forEach((id, guns) -> support.put(id, Set.copyOf(guns)));
            supportingGunIds = Map.copyOf(support);
            Map<String, Set<String>> selected = new HashMap<>();
            approvedIds.forEach((id, values) -> selected.put(id, Set.copyOf(values)));
            approvedIds = Map.copyOf(selected);
        }
        public Info info(String id) { return infos.get(id); }
        public Set<String> supportingGuns(String id) { return supportingGunIds.getOrDefault(id, Set.of()); }
    }

    public static final class GenerationSnapshot {
        private final Document original;
        private final Snapshot inventory;
        private final Document effective;
        private GenerationSnapshot(Document original, Snapshot inventory) {
            this.original = original; this.inventory = inventory; this.effective = applyWeights(original, inventory);
        }
        public Document effectiveDocument() { return effective; }
        public Snapshot inventory() { return inventory; }
        public List<String> specializedCompanionCandidates(ItemStack gun) {
            if (gun == null || gun.isEmpty()) return List.of();
            IGun access = IGun.getIGunOrNull(gun);
            if (access == null) return List.of();
            String gunId = access.getGunId(gun).toString();
            return companionCandidates(original, inventory, gunId).stream()
                    .filter(id -> AttachmentSupportIndex.canUse(gun, id)).toList();
        }
    }

    private AttachmentLootPolicy() {}

    public static int specialistThreshold(int loadedGunCount) { return Math.max(0, loadedGunCount) / 10; }

    public static boolean isSpecialist(String category, int supportCount, int loadedGunCount) {
        return supportsSpecialistCompanion(category) && supportCount > 0 && supportCount <= specialistThreshold(loadedGunCount);
    }

    public static String semanticCategory(String id, String physicalType) {
        if (SEMANTIC_GRIP_LASERS.contains(id) || "laser".equalsIgnoreCase(physicalType)) return "laser";
        return switch (physicalType == null ? "" : physicalType.toLowerCase(java.util.Locale.ROOT)) {
            case "scope", "muzzle", "grip", "stock", "ammo" -> physicalType.toLowerCase(java.util.Locale.ROOT);
            case "extended_mag", "magazine" -> "magazine";
            default -> "other";
        };
    }

    /** Capture once per catalog or generation task, after the native settings have settled. */
    public static Snapshot captureSnapshot() {
        var source = AttachmentSupportIndex.capture();
        Map<String, Info> infos = new HashMap<>();
        Map<String, Set<String>> support = new HashMap<>(source.supportingGunIds());
        source.physicalTypes().forEach((id, type) -> {
            String category = semanticCategory(id, type);
            int count = source.supportingGunIds().getOrDefault(id, Set.of()).size();
            infos.put(id, new Info(category, count, isSpecialist(category, count, source.loadedGunCount())));
        });
        source.ammoSupportingGunIds().forEach((id, guns) -> {
            infos.put(id, new Info("ammo", guns.size(), false)); support.put(id, guns);
        });
        Map<String, Set<String>> approved = new HashMap<>();
        approved.put("scope", APPROVED_SCOPES); approved.put("magazine", APPROVED_MAGAZINES);
        approved.put("laser", APPROVED_LASERS); approved.put("ammo", APPROVED_AMMO);
        for (String category : Set.of("stock", "muzzle", "grip")) approved.put(category,
                infos.entrySet().stream().filter(e -> category.equals(e.getValue().category()))
                        .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet()));
        approved.replaceAll((category, ids) -> ids.stream().filter(infos::containsKey).collect(Collectors.toUnmodifiableSet()));
        return new Snapshot(source.loadedGunCount(), infos, support, approved);
    }

    public static GenerationSnapshot captureSnapshot(Document document, ServerLevel level) {
        java.util.Objects.requireNonNull(level, "level");
        return new GenerationSnapshot(document, captureSnapshot());
    }

    /** Pure UI/server shared calculation; zero and smaller positive weights are never raised. */
    public static double effectiveWeight(Entry entry, GunSettings settings, Info info) {
        double base = entry.weight();
        if (!Double.isFinite(base) || base <= 0 || entry.maxCount() <= 0) return 0;
        if ("ammo".equals(entry.kind())) return settings.isExperimental(GunSettingCategory.AMMO)
                && !APPROVED_AMMO.contains(entry.id()) ? 0 : base;
        if (!"attachment".equals(entry.kind())) return base;
        if (info == null) return 0; // unloaded attachment is never an effective generation candidate
        GunSettingCategory category = settingCategory(info.category());
        if (category == null || !settings.isExperimental(category)) return base;
        return switch (info.category()) {
            case "scope" -> APPROVED_SCOPES.contains(entry.id()) ? base : 0;
            case "magazine" -> APPROVED_MAGAZINES.contains(entry.id()) ? base : 0;
            case "laser" -> APPROVED_LASERS.contains(entry.id()) ? base : 0;
            case "stock", "muzzle", "grip" -> info.supportedGunCount() <= 0 ? 0
                    : info.specialist() ? Math.min(base, SPECIALIST_WEIGHT_CAP) : base;
            default -> base;
        };
    }

    public static Document applyWeights(Document document, Snapshot snapshot) {
        List<Entry> entries = document.items().stream().map(entry -> entry.withWeight(
                effectiveWeight(entry, document.gunSettings(), snapshot.info(entry.id())))).toList();
        return new Document(document.general(), entries, document.gunSettings(), document.categoryProbabilities(), document.tierProfiles());
    }

    /** Pure candidate selection; runtime performs an additional actual-stack native permission check. */
    public static List<String> companionCandidates(Document original, Snapshot snapshot, String gunId) {
        Set<String> result = new LinkedHashSet<>();
        for (Entry entry : original.items()) {
            if (!"attachment".equals(entry.kind()) || !Double.isFinite(entry.weight()) || entry.weight() <= 0 || entry.maxCount() <= 0) continue;
            Info info = snapshot.info(entry.id());
            if (info == null || !info.specialist() || info.supportedGunCount() <= 0
                    || !supportsSpecialistCompanion(info.category())) continue;
            GunSettingCategory category = settingCategory(info.category());
            if (!original.gunSettings().isExperimental(category) || !snapshot.supportingGuns(entry.id()).contains(gunId)) continue;
            if (effectiveWeight(entry, original.gunSettings(), info) > 0) result.add(entry.id());
        }
        return List.copyOf(result);
    }

    public static List<String> specializedCompanionCandidates(ItemStack gun, Document document, ServerLevel level) {
        return captureSnapshot(document, level).specializedCompanionCandidates(gun);
    }

    private static boolean supportsSpecialistCompanion(String category) { return Set.of("stock", "muzzle", "grip").contains(category); }

    private static GunSettingCategory settingCategory(String category) {
        return switch (category) {
            case "scope" -> GunSettingCategory.SCOPES; case "magazine" -> GunSettingCategory.MAGAZINE;
            case "laser" -> GunSettingCategory.LASER; case "stock" -> GunSettingCategory.STOCK;
            case "muzzle" -> GunSettingCategory.MUZZLE; case "grip" -> GunSettingCategory.GRIP;
            case "ammo" -> GunSettingCategory.AMMO; default -> null;
        };
    }
}
