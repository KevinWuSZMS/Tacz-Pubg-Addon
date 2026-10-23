package com.example.examplemod.gunbalance.ammo;

import com.example.examplemod.localization.Texts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

/** Fixed, user-approved supply IDs; independent of weapon damage, physics, and existing stacks. */
public final class AmmoProfiles {
    public static final String RESOURCE = "/assets/tacz_pubg/experimental_ammo.json";
    private static final Catalog CATALOG = load();

    public record Profile(ResourceLocation gunId, ResourceLocation dataId,
            ResourceLocation originalAmmo, ResourceLocation targetAmmo) {
        public Profile {
            Objects.requireNonNull(gunId);
            Objects.requireNonNull(dataId);
            Objects.requireNonNull(originalAmmo);
        }
        public boolean applies() { return targetAmmo != null; }
        public boolean changesAmmo() { return applies() && !originalAmmo.equals(targetAmmo); }
    }

    record Catalog(Map<ResourceLocation, Profile> byGun, Map<ResourceLocation, Profile> byData,
            Set<ResourceLocation> targets) {}

    private AmmoProfiles() {}

    public static Profile forGun(ResourceLocation gunId) { return CATALOG.byGun().get(gunId); }
    public static int activeProfileCount() { return (int) CATALOG.byGun().values().stream().filter(Profile::applies).count(); }
    public static int changedProfileCount() { return (int) CATALOG.byGun().values().stream().filter(Profile::changesAmmo).count(); }
    public static Set<ResourceLocation> targetAmmoIds() { return CATALOG.targets(); }

    /** Disabled and unknown rows return the exact source. No live index, stack, or original pack is mutated. */
    public static JsonObject applyProfile(boolean experimental, ResourceLocation dataId, JsonObject freshSource) {
        if (!experimental) return freshSource;
        Profile profile = CATALOG.byData().get(dataId);
        if (profile == null || !profile.applies()) return freshSource;
        JsonElement ammo = freshSource.get("ammo");
        if (ammo == null || !ammo.isJsonPrimitive() || !ammo.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.missing_invalid_source_gun_ammo") + dataId);
        ResourceLocation sourceAmmo = ResourceLocation.parse(ammo.getAsString());
        if (!sourceAmmo.equals(profile.originalAmmo()) && !sourceAmmo.equals(profile.targetAmmo()))
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.approved_ammo_mapping_no_longer_matches_gunpack") + dataId
                    + ": " + sourceAmmo + " (expected " + profile.originalAmmo() + ")");
        if (sourceAmmo.equals(profile.targetAmmo())) return freshSource;
        JsonObject result = freshSource.deepCopy();
        result.addProperty("ammo", profile.targetAmmo().toString());
        return result;
    }

    /** Use the current reload's resource manager, never the previous TimelessAPI cache. */
    public static void validateTargetResources(Predicate<ResourceLocation> ammoIndexExists) {
        Objects.requireNonNull(ammoIndexExists);
        for (ResourceLocation id : CATALOG.targets()) {
            if (!ammoIndexExists.test(id))
                throw new IllegalStateException(Texts.tr("error.tacz_pubg.ammo_profiles.experimental_ammunition_requires_loaded_ammo_index") + id);
        }
    }

    static Catalog parse(JsonObject root) {
        if (!root.has("schemaVersion") || root.get("schemaVersion").getAsInt() != 1)
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.unsupported_experimental_ammo_schema"));
        Map<ResourceLocation, Profile> byGun = new HashMap<>();
        Map<ResourceLocation, Profile> byData = new HashMap<>();
        Set<ResourceLocation> declaredTargets = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("targetAmmoIds")) {
            ResourceLocation id = ResourceLocation.parse(element.getAsString());
            if (!declaredTargets.add(id)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.duplicate_target_ammo") + id);
        }
        Set<ResourceLocation> usedTargets = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("guns")) {
            JsonObject row = element.getAsJsonObject();
            boolean apply = switch (row.get("action").getAsString()) {
                case "apply" -> true;
                case "preserve" -> false;
                default -> throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.unknown_ammo_mapping_action"));
            };
            JsonElement targetElement = row.get("targetAmmo");
            ResourceLocation target = targetElement == null || targetElement.isJsonNull() ? null
                    : ResourceLocation.parse(targetElement.getAsString());
            if (apply != (target != null)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.ammo_action_target_disagree"));
            Profile profile = new Profile(ResourceLocation.parse(row.get("gunId").getAsString()),
                    ResourceLocation.parse(row.get("dataId").getAsString()),
                    ResourceLocation.parse(row.get("originalAmmo").getAsString()), target);
            if (byGun.putIfAbsent(profile.gunId(), profile) != null)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.duplicate_ammo_gun_mapping") + profile.gunId());
            Profile previous = byData.putIfAbsent(profile.dataId(), profile);
            if (previous != null && (!previous.originalAmmo().equals(profile.originalAmmo())
                    || !Objects.equals(previous.targetAmmo(), profile.targetAmmo())))
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.conflicting_shared_data_ammo_mapping") + profile.dataId());
            if (target != null) usedTargets.add(target);
        }
        if (!declaredTargets.equals(usedTargets))
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ammo_profiles.declared_ammo_catalog_differs_from_used_targets"));
        return new Catalog(Map.copyOf(byGun), Map.copyOf(byData), Set.copyOf(usedTargets));
    }

    private static Catalog load() {
        try (var stream = AmmoProfiles.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", RESOURCE));
            return parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception error) {
            // A partly applied supply catalog could silently grant ordinary ammo to a rare weapon.
            throw new IllegalStateException(Texts.tr("error.tacz_pubg.ammo_profiles.invalid_approved_experimental_ammo_catalog"), error);
        }
    }
}
