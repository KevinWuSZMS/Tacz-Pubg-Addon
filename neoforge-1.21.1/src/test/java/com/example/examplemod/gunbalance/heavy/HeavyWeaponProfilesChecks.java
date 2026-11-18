package com.example.examplemod.gunbalance.heavy;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.example.examplemod.gunbalance.GunDataExperimentPipeline;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

/** Approved targets checked against all thirteen fresh installed gun snapshots, without starting a world. */
public final class HeavyWeaponProfilesChecks {
    private static int checks;
    private static final String MARKER = "tacz_pubg_heavy_profile";
    private static final Map<String, double[]> APPROVED = Map.ofEntries(
            Map.entry("cib:dprkrpg", new double[] {10, 38, 10, 6, 1}),
            Map.entry("cib:dzj08", new double[] {8, 30, 8, 6, 1}),
            Map.entry("cib:qlu11", new double[] {5, 18, 8, 3, .6}),
            Map.entry("classicr:mgl_40mm", new double[] {4, 18, 8, 3, .6}),
            Map.entry("rainforest:at4", new double[] {10, 34, 9, 6, 1}),
            Map.entry("rainforest:m72", new double[] {8, 30, 8, 6, 1}),
            Map.entry("suffuse:aiyasinrpg", new double[] {12, 38, 10, 6, 1}),
            Map.entry("suffuse:m203", new double[] {4, 24, 8, 3, .6}),
            Map.entry("suffuse:m79", new double[] {5, 26, 8, 3, .6}),
            Map.entry("suffuse:pf98a", new double[] {14, 44, 12, 6.5, 1.2}),
            Map.entry("suffuse:qlu11", new double[] {5, 20, 8, 3, .6}),
            Map.entry("tacz:m320", new double[] {5, 26, 8, 3, .6}),
            Map.entry("tacz:rpg7", new double[] {10, 34, 9, 6, 1}));

    public static void main(String[] args) throws Exception {
        JsonObject exported;
        try (var stream = HeavyWeaponProfiles.class.getResourceAsStream(HeavyWeaponProfiles.RESOURCE)) {
            require(stream != null, "Approved heavy resource packaged");
            exported = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var catalog = HeavyWeaponProfiles.parse(exported);
        require(catalog.byGun().size() == 13 && catalog.byData().size() == 13
                && HeavyWeaponProfiles.activeProfileCount() == 13, "All thirteen approved gun/data identities loaded atomically");
        Path root = Path.of("test-inputs");
        JsonObject assets = JsonParser.parseString(Files.readString(root.resolve("gun-assets.json"))).getAsJsonObject();
        Map<String, JsonObject> sources = new HashMap<>();
        for (JsonElement element : assets.getAsJsonArray("guns")) {
            JsonObject row = element.getAsJsonObject(); sources.put(row.get("id").getAsString(), row);
        }
        require(sources.size() == 424, "Installed source snapshot complete");
        int experimentalCombinations = 0;
        for (var profile : catalog.byGun().values()) {
            double[] approved = APPROVED.get(profile.gunId().toString());
            require(approved != null, "Every resource target has user-approved identity");
            near(profile.directDamage(), approved[0], "Approved direct target");
            near(profile.explosionDamage(), approved[1], "Approved blast target");
            near(profile.damageRadius(), approved[2], "Approved entity radius");
            near(profile.terrainPower(), approved[3], "Approved terrain power independent of entity radius");
            near(profile.maximumCenterKnockbackImpulse(), approved[4], "Approved center impulse cap");
            require(profile.curveP() >= 1 && profile.curveP() <= 2 && profile.curveQ() >= 1 && profile.curveQ() <= 2, "Shape sliders bounded");
            require(!profile.grenade() || profile.terrainPower() == 3, "Every approved grenade uses normal creeper terrain energy");
            require(profile.equals(HeavyWeaponProfiles.forGun(profile.gunId()))
                    && profile.equals(HeavyWeaponProfiles.forData(profile.dataId())), "Both identity maps expose the same approved profile");
            JsonObject asset = sources.get(profile.gunId().toString());
            require(asset != null && asset.getAsJsonObject("rawIndex").get("data").getAsString().equals(profile.dataId().toString()),
                    "Approved data identity matches installed native index");
            JsonObject source = asset.getAsJsonObject("rawData");
            String original = source.toString();
            require(!source.has(MARKER), "Installed native source has an original identity");
            JsonObject transformed = HeavyWeaponProfiles.transform(profile.dataId(), source);
            require(transformed != source && source.toString().equals(original), "Transform deep-copies the fresh source");
            assertTargets(transformed, profile);
            assertRetained(source, transformed);
            require(HeavyWeaponProfiles.transform(profile.dataId(), source).equals(transformed), "Repeated fresh transform is deterministic");
            equivalent(HeavyWeaponProfiles.transform(profile.dataId(), transformed), transformed, "Even a repeated transformed damage curve keeps its targets");
            for (boolean balance : new boolean[] {false, true}) for (boolean ammo : new boolean[] {false, true}) {
                experimentalCombinations++;
                JsonObject result = GunDataExperimentPipeline.apply(profile.dataId(), source, balance, ammo);
                require(source.toString().equals(original), "Four switch combinations preserve native source data");
                require(result.has(MARKER) == balance, "Profile marker follows BALANCE independently of ammo mode");
                JsonObject onlyAmmo = GunDataExperimentPipeline.apply(profile.dataId(), source, false, ammo);
                require(Objects.equals(result.get("ammo"), onlyAmmo.get("ammo")), "Balance preserves the independent supply identity");
                if (balance) {
                    assertTargets(result, profile);
                    assertRetainedMechanics(source, result);
                } else require(result.equals(onlyAmmo), "Original balance keeps every native combat field");
                require(GunDataExperimentPipeline.apply(profile.dataId(), source, balance, ammo).equals(result), "Reload starts from the same authored snapshot");
                JsonObject restored = GunDataExperimentPipeline.apply(profile.dataId(), source, false, false);
                require(restored == source && restored.toString().equals(original) && !restored.has(MARKER), "Original settings restore the exact complete source");
                JsonObject roundTrip = JsonParser.parseString(result.toString()).getAsJsonObject();
                require(roundTrip.equals(result) && Objects.equals(roundTrip.get(MARKER), result.get(MARKER)), "Network JSON round trip retains exact profile identity");
            }
        }
        require(experimentalCombinations == 52, "Thirteen guns cover all four independent setting combinations");
        for (var row : sources.entrySet()) {
            if (APPROVED.containsKey(row.getKey())) continue;
            ResourceLocation dataId = ResourceLocation.parse(row.getValue().getAsJsonObject("rawIndex").get("data").getAsString());
            JsonObject source = row.getValue().getAsJsonObject("rawData");
            require(HeavyWeaponProfiles.transform(dataId, source) == source, "Other 411 source snapshots retain identity");
            require(!GunDataExperimentPipeline.apply(dataId, source, true, true).has(MARKER), "Other weapon experiments carry their own authored properties");
        }
        ResourceLocation unknown = ResourceLocation.parse("test:private_heavy_data");
        require(HeavyWeaponProfiles.forGun(unknown) == null && HeavyWeaponProfiles.forData(unknown) == null, "Unknown gun/data returns no profile");
        JsonObject privateSource = JsonParser.parseString("{\"bullet\":{\"damage\":17},\"private\":42}").getAsJsonObject();
        require(HeavyWeaponProfiles.transform(unknown, privateSource) == privateSource, "Unknown source identity retained");
        modeAndCurveChecks();
        malformedCatalogChecks(exported);
        System.out.println("Approved heavy weapon profile checks passed: " + checks + "; 13 approved, 411 preserved, 52 setting combinations");
    }

    private static void assertTargets(JsonObject result, HeavyWeaponProfiles.Profile profile) {
        JsonObject bullet = result.getAsJsonObject("bullet"), extra = bullet.getAsJsonObject("extra_damage");
        JsonArray curve = extra.getAsJsonArray("damage_adjust");
        double near = curve == null || curve.isEmpty() ? bullet.get("damage").getAsDouble() : curve.get(0).getAsJsonObject().get("damage").getAsDouble();
        near(near, profile.directDamage(), "Near-distance damage uses the authored first native segment");
        near(extra.get("head_shot_multiplier").getAsDouble(), 1, "Headshot multiplier target");
        near(extra.get("armor_ignore").getAsDouble(), 0, "Armor-ignore target");
        require(bullet.get("pierce").getAsInt() == 1, "Pierce target");
        JsonObject explosion = bullet.getAsJsonObject("explosion");
        near(explosion.get("damage").getAsDouble(), profile.explosionDamage(), "Explosion damage target");
        near(explosion.get("radius").getAsDouble(), profile.damageRadius(), "Entity damage radius target");
        require(explosion.get("explode").getAsBoolean() && explosion.get("destroy_block").getAsBoolean(), "Experimental native explosion and terrain default enabled");
        require(result.get(MARKER).getAsString().equals(profile.gunId().toString()), "Exact gun marker links to independent terrain/impulse profile");
        JsonObject modes = result.getAsJsonObject("fire_mode_adjust");
        if (modes != null) for (JsonElement element : modes.asMap().values()) {
            JsonObject mode = element.getAsJsonObject();
            for (String field : new String[] {"damage", "head_shot_multiplier", "armor_ignore"})
                near(mode.get(field).getAsDouble(), 0, "Firing-mode damage increments retain approved targets");
        }
    }

    private static void assertRetained(JsonObject source, JsonObject result) {
        for (String field : source.keySet()) if (!Set.of("bullet", "fire_mode_adjust").contains(field))
            require(source.get(field).equals(result.get(field)), "All other authored top-level values retained: " + field);
        assertRetainedMechanics(source, result);
    }

    private static void assertRetainedMechanics(JsonObject source, JsonObject result) {
        for (String field : new String[] {"rpm", "script", "script_param", "ammo_amount", "extended_mag_ammo_amount",
                "bolt", "reload", "fire_mode", "burst_data", "exclusive_attachments", "builtin_attachments", "allow_attachment_types"})
            require(Objects.equals(source.get(field), result.get(field)), "Authored action and capacity retained: " + field);
        JsonObject oldBullet = source.getAsJsonObject("bullet"), bullet = result.getAsJsonObject("bullet");
        for (String field : oldBullet.keySet()) if (!Set.of("damage", "extra_damage", "pierce", "explosion").contains(field))
            require(oldBullet.get(field).equals(bullet.get(field)), "Native trajectory and other projectile mechanics retained: " + field);
        JsonObject oldExtra = oldBullet.getAsJsonObject("extra_damage"), extra = bullet.getAsJsonObject("extra_damage");
        if (oldExtra != null) for (String field : oldExtra.keySet()) if (!Set.of("armor_ignore", "head_shot_multiplier", "damage_adjust").contains(field))
            require(oldExtra.get(field).equals(extra.get(field)), "Other extra-damage parameters retained: " + field);
        JsonArray oldCurve = oldExtra == null ? null : oldExtra.getAsJsonArray("damage_adjust");
        JsonArray curve = extra.getAsJsonArray("damage_adjust");
        require((oldCurve == null) == (curve == null), "Distance table presence retained");
        double oldNear = oldCurve == null || oldCurve.isEmpty() ? oldBullet.get("damage").getAsDouble() : oldCurve.get(0).getAsJsonObject().get("damage").getAsDouble();
        double newNear = curve == null || curve.isEmpty() ? bullet.get("damage").getAsDouble() : curve.get(0).getAsJsonObject().get("damage").getAsDouble();
        near(bullet.get("damage").getAsDouble(), oldBullet.get("damage").getAsDouble() * newNear / oldNear, "Fallback whole-shot damage scaled with near range");
        if (oldCurve != null) {
            require(curve.size() == oldCurve.size(), "Authored distance segment count retained");
            for (int i = 0; i < curve.size(); i++) {
                JsonObject oldSegment = oldCurve.get(i).getAsJsonObject(), segment = curve.get(i).getAsJsonObject();
                require(oldSegment.keySet().equals(segment.keySet()), "All distance segment keys retained");
                for (String field : oldSegment.keySet()) if (!field.equals("damage"))
                    require(oldSegment.get(field).equals(segment.get(field)), "Authored cutoff and custom distance metadata retained");
                near(segment.get("damage").getAsDouble(), oldSegment.get("damage").getAsDouble() * newNear / oldNear, "Every segment retains relative damage falloff");
            }
        }
        JsonObject oldExplosion = oldBullet.getAsJsonObject("explosion"), explosion = bullet.getAsJsonObject("explosion");
        if (oldExplosion != null) for (String field : oldExplosion.keySet())
            if (!Set.of("explode", "damage", "radius", "destroy_block").contains(field))
                require(oldExplosion.get(field).equals(explosion.get(field)), "Explosion delay, sound and authored effects retained: " + field);
        JsonObject oldModes = source.getAsJsonObject("fire_mode_adjust"), modes = result.getAsJsonObject("fire_mode_adjust");
        require((oldModes == null) == (modes == null), "Fire-mode override structure retained");
        if (oldModes != null) {
            require(oldModes.keySet().equals(modes.keySet()), "Authored firing modes retained");
            for (String modeName : oldModes.keySet()) for (String field : oldModes.getAsJsonObject(modeName).keySet())
                if (!Set.of("damage", "head_shot_multiplier", "armor_ignore").contains(field))
                    require(oldModes.getAsJsonObject(modeName).get(field).equals(modes.getAsJsonObject(modeName).get(field)), "Mode RPM/speed/action retained: " + field);
        }
    }

    private static void modeAndCurveChecks() {
        ResourceLocation dataId = HeavyWeaponProfiles.forGun(ResourceLocation.parse("rainforest:m72")).dataId();
        JsonObject synthetic = JsonParser.parseString("""
                {"rpm":60,"private":true,"fire_mode_adjust":{"semi":{"damage":20,"head_shot_multiplier":3,"armor_ignore":0.6,"rpm":10,"speed":12,"private":true}},
                 "bullet":{"damage":15,"speed":120,"extra_damage":{"damage_adjust":[{"distance":5,"damage":20,"custom":"a"},{"distance":10,"damage":18},{"distance":"infinite","damage":12}]},
                 "explosion":{"damage":1000,"radius":3,"delay":30,"custom":"effect"}}}
                """).getAsJsonObject();
        JsonObject result = HeavyWeaponProfiles.transform(dataId, synthetic);
        assertTargets(result, HeavyWeaponProfiles.forData(dataId));
        assertRetained(synthetic, result);
        near(result.getAsJsonObject("bullet").get("damage").getAsDouble(), 6, "M72 raw fallback uses first-segment ratio rather than target substitution");
        JsonArray table = result.getAsJsonObject("bullet").getAsJsonObject("extra_damage").getAsJsonArray("damage_adjust");
        near(table.get(0).getAsJsonObject().get("damage").getAsDouble(), 8, "M72 near target");
        near(table.get(1).getAsJsonObject().get("damage").getAsDouble(), 7.2, "M72 middle segment");
        near(table.get(2).getAsJsonObject().get("damage").getAsDouble(), 4.8, "M72 infinite cutoff keeps relative falloff");
        JsonObject bad = synthetic.deepCopy();
        bad.getAsJsonObject("bullet").getAsJsonObject("extra_damage").getAsJsonArray("damage_adjust").get(0).getAsJsonObject().addProperty("damage", 0);
        String snapshot = bad.toString();
        reject(() -> HeavyWeaponProfiles.transform(dataId, bad), "Invalid source near damage rejected before caching");
        require(bad.toString().equals(snapshot), "Rejected transform preserves original source");
    }

    private static void malformedCatalogChecks(JsonObject original) {
        JsonObject bad = original.deepCopy(); bad.addProperty("schemaVersion", 2);
        reject(() -> HeavyWeaponProfiles.parse(bad), "Unknown schema rejected");
        JsonObject extraKey = original.deepCopy(); extraKey.addProperty("other", true);
        reject(() -> HeavyWeaponProfiles.parse(extraKey), "Unknown catalog property rejected");
        JsonObject missing = original.deepCopy(); missing.getAsJsonArray("guns").get(0).getAsJsonObject().remove("terrainPower");
        reject(() -> HeavyWeaponProfiles.parse(missing), "Missing profile target rejected");
        JsonObject extra = original.deepCopy(); extra.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty("unapproved", 1);
        reject(() -> HeavyWeaponProfiles.parse(extra), "Unknown profile target rejected");
        JsonObject duplicate = original.deepCopy(); duplicate.getAsJsonArray("guns").add(duplicate.getAsJsonArray("guns").get(0).deepCopy());
        reject(() -> HeavyWeaponProfiles.parse(duplicate), "Duplicate gun/data identity rejected atomically");
        JsonObject sharedData = original.deepCopy(); sharedData.getAsJsonArray("guns").get(1).getAsJsonObject().add("dataId", sharedData.getAsJsonArray("guns").get(0).getAsJsonObject().get("dataId"));
        reject(() -> HeavyWeaponProfiles.parse(sharedData), "Shared data identity rejected");
        for (String field : new String[] {"directDamage", "explosionDamage", "damageRadius", "terrainPower", "maximumCenterKnockbackImpulse"}) {
            JsonObject zero = original.deepCopy(); zero.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty(field, 0);
            reject(() -> HeavyWeaponProfiles.parse(zero), "Zero target rejected: " + field);
            JsonObject text = original.deepCopy(); text.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty(field, "1");
            reject(() -> HeavyWeaponProfiles.parse(text), "Numeric strings rejected: " + field);
            JsonObject infinite = original.deepCopy(); infinite.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty(field, Double.POSITIVE_INFINITY);
            reject(() -> HeavyWeaponProfiles.parse(infinite), "Infinite target rejected: " + field);
        }
        for (String field : new String[]{"curveP", "curveQ"}) for (double value : new double[]{.999, 2.001, Double.NaN}) {
            JsonObject badCurve = original.deepCopy(); badCurve.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty(field, value);
            reject(() -> HeavyWeaponProfiles.parse(badCurve), "Bounded shape coefficient " + field);
        }
        JsonObject textGrenade = original.deepCopy(); textGrenade.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty("grenade", "true");
        reject(() -> HeavyWeaponProfiles.parse(textGrenade), "Grenade classification requires a boolean");
        JsonObject namespace = original.deepCopy(); namespace.getAsJsonArray("guns").get(0).getAsJsonObject().addProperty("gunId", "gun");
        reject(() -> HeavyWeaponProfiles.parse(namespace), "Explicit namespace required");
        JsonObject empty = original.deepCopy(); empty.add("guns", new JsonArray());
        reject(() -> HeavyWeaponProfiles.parse(empty), "Empty partial catalog rejected");
    }

    private static void equivalent(JsonElement actual, JsonElement expected, String why) {
        if (actual.isJsonObject() && expected.isJsonObject()) {
            require(actual.getAsJsonObject().keySet().equals(expected.getAsJsonObject().keySet()), why);
            for (String field : actual.getAsJsonObject().keySet()) equivalent(actual.getAsJsonObject().get(field), expected.getAsJsonObject().get(field), why);
        } else if (actual.isJsonArray() && expected.isJsonArray()) {
            require(actual.getAsJsonArray().size() == expected.getAsJsonArray().size(), why);
            for (int i = 0; i < actual.getAsJsonArray().size(); i++) equivalent(actual.getAsJsonArray().get(i), expected.getAsJsonArray().get(i), why);
        } else if (actual.isJsonPrimitive() && expected.isJsonPrimitive() && actual.getAsJsonPrimitive().isNumber() && expected.getAsJsonPrimitive().isNumber())
            near(actual.getAsDouble(), expected.getAsDouble(), why);
        else require(actual.equals(expected), why);
    }
    private static void near(double actual, double expected, String why) { require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-8, why + ": " + actual + " != " + expected); }
    private static void reject(Runnable operation, String why) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(why);
    }
    private static void require(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
