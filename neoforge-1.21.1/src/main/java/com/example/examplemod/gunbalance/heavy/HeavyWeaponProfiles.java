package com.example.examplemod.gunbalance.heavy;

import com.example.examplemod.localization.Texts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;

/** Approved heavy-weapon damage and independent terrain/knockback targets. */
public final class HeavyWeaponProfiles {
    public static final String RESOURCE = "/data/tacz_pubg/gun_balance/heavy_weapons.json";
    private static final Set<String> FIELDS = Set.of("gunId", "dataId", "directDamage", "explosionDamage",
            "damageRadius", "terrainPower", "maximumCenterKnockbackImpulse", "curveP", "curveQ", "grenade");
    private static final Catalog CATALOG = load();

    public record Profile(ResourceLocation gunId, ResourceLocation dataId, double directDamage,
            double explosionDamage, double damageRadius, double terrainPower,
            double maximumCenterKnockbackImpulse, double curveP, double curveQ, boolean grenade) {
        public Profile {
            if (gunId == null || dataId == null || !positive(directDamage, 10000)
                    || !positive(explosionDamage, 10000) || !positive(damageRadius, 128)
                    || !positive(terrainPower, 128) || !positive(maximumCenterKnockbackImpulse, 16)
                    || !Double.isFinite(curveP) || curveP < 1 || curveP > 2 || !Double.isFinite(curveQ) || curveQ < 1 || curveQ > 2)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_profile_requires_valid_identities_and_finite_positive_targets"));
        }
    }

    public static Profile withCurve(Profile source, com.example.examplemod.zones.heavy.HeavyWeaponDamageCurve curve) {
        if (source == null || curve == null) return source;
        return new Profile(source.gunId(), source.dataId(), source.directDamage(), source.explosionDamage(),
                curve.damageRadius(), source.terrainPower(), source.maximumCenterKnockbackImpulse(), curve.p(), curve.q(), source.grenade());
    }

    record Catalog(Map<ResourceLocation, Profile> byGun, Map<ResourceLocation, Profile> byData) {}

    private HeavyWeaponProfiles() {}

    public static Profile forGun(ResourceLocation gunId) { return CATALOG.byGun().get(gunId); }
    public static Profile forData(ResourceLocation dataId) { return CATALOG.byData().get(dataId); }
    public static int activeProfileCount() { return CATALOG.byGun().size(); }

    /** The caller supplies a fresh gunpack snapshot for each reload. Distance cutoffs retain authored values. */
    public static JsonObject transform(ResourceLocation dataId, JsonObject source) {
        Profile profile = forData(dataId);
        if (profile == null) return source;
        JsonObject result = source.deepCopy();
        result.addProperty("tacz_pubg_heavy_profile", profile.gunId().toString());
        JsonObject bullet = object(result, "bullet");
        JsonObject extra = object(bullet, "extra_damage");
        JsonArray curve = array(extra, "damage_adjust");
        double rawDamage = numeric(bullet.get("damage"), "bullet.damage");
        double nearDamage = curve == null || curve.isEmpty() ? rawDamage
                : numeric(curve.get(0).getAsJsonObject().get("damage"), Texts.tr("error.tacz_pubg.heavy_weapon_profiles.near_damage"));
        if (!positive(nearDamage, Double.MAX_VALUE))
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_source_near_damage_must_be_positive"));
        double factor = profile.directDamage() / nearDamage;
        bullet.addProperty("damage", rawDamage * factor);
        if (curve != null) for (JsonElement element : curve) {
            JsonObject segment = element.getAsJsonObject();
            segment.addProperty("damage", numeric(segment.get("damage"), Texts.tr("error.tacz_pubg.heavy_weapon_profiles.distance_damage")) * factor);
        }
        extra.addProperty("head_shot_multiplier", 1.0);
        extra.addProperty("armor_ignore", 0.0);
        bullet.addProperty("pierce", 1);
        JsonObject explosion = object(bullet, "explosion");
        explosion.addProperty("explode", true);
        explosion.addProperty("damage", profile.explosionDamage());
        explosion.addProperty("radius", profile.damageRadius());
        explosion.addProperty("destroy_block", true);
        JsonElement modes = result.get("fire_mode_adjust");
        if (modes != null && modes.isJsonObject()) for (JsonElement element : modes.getAsJsonObject().asMap().values()) {
            if (!element.isJsonObject()) continue;
            JsonObject mode = element.getAsJsonObject();
            mode.addProperty("damage", 0.0);
            mode.addProperty("head_shot_multiplier", 0.0);
            mode.addProperty("armor_ignore", 0.0);
        }
        return result;
    }

    static Catalog parse(JsonObject root) {
        exactKeys(root, Set.of("schemaVersion", "guns"), Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_catalog"));
        double schema = numeric(root.get("schemaVersion"), "schemaVersion");
        if (schema != 1 || !root.get("guns").isJsonArray())
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_catalog_requires_schemaversion_1_and_a_guns_array"));
        JsonArray guns = root.getAsJsonArray("guns");
        if (guns.isEmpty() || guns.size() > 256) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_catalog_size_must_be_1_256"));
        Map<ResourceLocation, Profile> byGun = new LinkedHashMap<>(), byData = new LinkedHashMap<>();
        for (JsonElement element : guns) {
            if (!element.isJsonObject()) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_entry_must_be_an_object"));
            JsonObject row = element.getAsJsonObject();
            exactKeys(row, FIELDS, Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_entry"));
            Profile profile = new Profile(identity(row.get("gunId"), "gunId"), identity(row.get("dataId"), "dataId"),
                    numeric(row.get("directDamage"), "directDamage"), numeric(row.get("explosionDamage"), "explosionDamage"),
                    numeric(row.get("damageRadius"), "damageRadius"), numeric(row.get("terrainPower"), "terrainPower"),
                    numeric(row.get("maximumCenterKnockbackImpulse"), "maximumCenterKnockbackImpulse"),
                    numeric(row.get("curveP"), "curveP"), numeric(row.get("curveQ"), "curveQ"), bool(row.get("grenade"), "grenade"));
            if (byGun.putIfAbsent(profile.gunId(), profile) != null || byData.putIfAbsent(profile.dataId(), profile) != null)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.heavy_weapon_profiles.heavy_weapon_identities_must_be_unique") + profile.gunId());
        }
        return new Catalog(Map.copyOf(byGun), Map.copyOf(byData));
    }

    private static Catalog load() {
        try (var stream = HeavyWeaponProfiles.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", RESOURCE));
            return parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception error) {
            LogUtils.getLogger().error("Unable to load approved heavy weapon profiles; retaining source heavy weapon parameters", error);
            return new Catalog(Map.of(), Map.of());
        }
    }

    private static boolean positive(double value, double maximum) { return Double.isFinite(value) && value > 0 && value <= maximum; }
    private static double numeric(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(field + Texts.tr("error.tacz_pubg.heavy_weapon_profiles.must_be_numeric"));
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException(field + Texts.tr("error.tacz_pubg.heavy_weapon_profiles.must_be_finite"));
        return number;
    }
    private static boolean bool(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(field + Texts.tr("error.tacz_pubg.heavy_weapon_profiles.must_be_boolean"));
        return value.getAsBoolean();
    }
    private static ResourceLocation identity(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !value.getAsString().contains(":")) throw new IllegalArgumentException(field + Texts.tr("error.tacz_pubg.heavy_weapon_profiles.must_be_a_namespaced_identity"));
        return ResourceLocation.parse(value.getAsString());
    }
    private static void exactKeys(JsonObject object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) throw new IllegalArgumentException(label + Texts.tr("error.tacz_pubg.heavy_weapon_profiles.requires_exactly") + expected);
    }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value != null && value.isJsonObject()) return value.getAsJsonObject();
        JsonObject result = new JsonObject(); parent.add(key, result); return result;
    }
    private static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsJsonArray();
    }
}
