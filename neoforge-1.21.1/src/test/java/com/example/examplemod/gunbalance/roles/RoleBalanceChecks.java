package com.example.examplemod.gunbalance.roles;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Full installed catalog audit with native TaCZ float/strict-threshold damage semantics. */
public final class RoleBalanceChecks {
    private static int checks;
    private static final String RESOURCE = "/assets/tacz_pubg/approved_role_balance.json";

    public static void main(String[] args) throws Exception {
        JsonObject exported;
        try (var stream = RoleBalanceChecks.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new AssertionError("Missing approved role resource");
            exported = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        RoleBalanceCatalog catalog = RoleBalanceCatalog.parse(exported);
        require(catalog.byGun().size() == 424, "All installed guns represented");
        require(catalog.byGun().values().stream().filter(g -> g.target() != null).count() == 386, "All approved role targets represented");
        Path root = Path.of("test-inputs");
        JsonObject assets = JsonParser.parseString(Files.readString(root.resolve("gun-assets.json"))).getAsJsonObject();
        Map<String, JsonObject> sources = new HashMap<>();
        for (JsonElement element : assets.getAsJsonArray("guns")) sources.put(element.getAsJsonObject().get("id").getAsString(), element.getAsJsonObject());
        for (RoleBalanceCatalog.Entry entry : catalog.byGun().values()) {
            JsonObject asset = sources.get(entry.gunId());
            require(asset != null, "Approval gun is installed: " + entry.gunId());
            require(entry.dataId().equals(asset.getAsJsonObject("rawIndex").get("data").getAsString()), "Approval data reference matches installed snapshot");
            JsonObject source = asset.getAsJsonObject("rawData");
            String original = source.toString();
            require(RoleBalanceTransform.applyProfile(false, source, entry.target()) == source, "Initial returns source identity");
            JsonObject result = RoleBalanceTransform.applyProfile(true, source, entry.target());
            require(original.equals(source.toString()), "Source is never mutated");
            if (entry.target() == null) { require(result == source, "Special role is preserved"); continue; }
            var target = entry.target();
            require(result != source, "Approved transform works on fresh copy");
            JsonObject bullet = result.getAsJsonObject("bullet");
            JsonObject extra = bullet.getAsJsonObject("extra_damage");
            JsonArray table = extra.getAsJsonArray("damage_adjust");
            near(bullet.get("damage").getAsDouble(), target.damage10(), 1e-10, "JSON damage remains whole-shot; native engine divides pellets");
            near(extra.get("head_shot_multiplier").getAsDouble(), target.head(), 0, "Head target");
            near(result.get("aim_time").getAsDouble(), target.ads(), 0, "ADS target");
            near(result.getAsJsonObject("inaccuracy").get("stand").getAsDouble(), target.hip(), 0, "Stand target");
            near(result.getAsJsonObject("inaccuracy").get("aim").getAsDouble(), target.aim(), 0, "Aim target");
            // Preserve authored mechanics, exterior physics and the prior recoil control curve.
            for (String key : source.keySet()) if (!java.util.Set.of("bullet", "aim_time", "inaccuracy", "rpm", "fire_mode_adjust", "burst_data").contains(key))
                require(source.get(key).equals(result.get(key)), "Unrelated mechanic preserved: " + key);
            for (String key : source.getAsJsonObject("bullet").keySet()) if (!java.util.Set.of("damage", "extra_damage").contains(key))
                require(source.getAsJsonObject("bullet").get(key).equals(bullet.get(key)), "Bullet physics/behavior preserved: " + key);
            JsonObject oldExtra = source.getAsJsonObject("bullet").getAsJsonObject("extra_damage");
            if (oldExtra != null) for (String key : oldExtra.keySet()) if (!java.util.Set.of("head_shot_multiplier", "damage_adjust").contains(key))
                require(oldExtra.get(key).equals(extra.get(key)), "Armor/other extra damage preserved");
            for (var knot : target.envelope()) near(nativeShotDamage(table, knot.distance(), target.pellets()),
                    target.damage10() * knot.retained(), 3e-6, "Exact approved distance knot " + knot.distance());
            double previousDamage = Double.POSITIVE_INFINITY;
            for (double d = 0; d <= 600; d += .5) {
                double damage = nativeShotDamage(table, d, target.pellets());
                require(Double.isFinite(damage) && damage >= 0 && damage <= previousDamage + 1e-6, "Damage envelope stays finite, monotone, nonnegative");
                previousDamage = damage;
                // 5m left hold differs from linear review envelope by at most one 5m step.
                double expected = target.damage10() * RoleBalanceTransform.retention(target, d);
                require(damage + 3e-6 >= expected && damage <= target.damage10() * RoleBalanceTransform.retention(target, Math.max(0, d - 5)) + 3e-6,
                        "Native step approximates approved linear envelope");
            }
            double expectedPlateau = 10;
            for (var knot : target.envelope()) { if (knot.retained() != 1) break; expectedPlateau = knot.distance(); }
            near(table.get(0).getAsJsonObject().get("distance").getAsDouble(), expectedPlateau, 0, "Native first effective range is full-damage plateau");
            JsonObject modeAdjust = result.getAsJsonObject("fire_mode_adjust");
            for (String modeName : modeAdjust.keySet()) {
                JsonObject mode = modeAdjust.getAsJsonObject(modeName);
                for (String key : new String[] {"damage", "head_shot_multiplier", "aim_inaccuracy", "other_inaccuracy"})
                    near(mode.get(key).getAsDouble(), 0, 0, "No firing-mode escape: " + key);
                int effectiveRpm = result.get("rpm").getAsInt() + mode.get("rpm").getAsInt();
                require(effectiveRpm > 0 && effectiveRpm <= target.rpmCap() && effectiveRpm <= target.rpm(), "Every firing mode respects role cap");
                require(60000.0 / (60000 / effectiveRpm) <= target.rpmCap() + 1e-8, "Native integer-millisecond timer respects role cap");
                require(effectiveRpm <= nativeRpm(source, modeName), "No mode is sped up");
                for (var knot : target.envelope()) {
                    double headDamage = nativeShotDamage(table, knot.distance(), target.pellets()) * target.head();
                    if (entry.role().equals("dmr")) require(headDamage < 20, "Ordinary DMR cannot become one-shot-head in another mode");
                    if (entry.role().equals("heavy_semi")) require(nativeShotDamage(table, knot.distance(), target.pellets()) < 20, "Heavy semi remains below one-shot-body");
                }
            }
            if (modeAdjust.has("burst")) {
                JsonObject burst = result.getAsJsonObject("burst_data");
                int count = burst.has("count") ? Math.max(1, burst.get("count").getAsInt()) : 3;
                require(burst.get("bpm").getAsInt() <= target.rpmCap(), "Burst inter-shot BPM capped");
                require(60000.0 / (60000 / burst.get("bpm").getAsInt()) <= target.rpmCap() + 1e-8, "Native burst timer respects role cap");
                require(60.0 * count / burst.get("min_interval").getAsDouble() <= target.rpmCap() + 1e-8, "Whole burst cadence capped");
            }
            JsonObject repeated = RoleBalanceTransform.apply(result, target);
            require(repeated.getAsJsonObject("bullet").equals(bullet), "Damage does not compound");
            require(repeated.getAsJsonObject("fire_mode_adjust").equals(modeAdjust), "Modes do not compound");
            for (String posture : new String[] {"aim", "stand", "move", "sneak", "lie"})
                near(repeated.getAsJsonObject("inaccuracy").get(posture).getAsDouble(), result.getAsJsonObject("inaccuracy").get(posture).getAsDouble(), 1e-10, "Spread does not compound");
        }
        badCatalogChecks(exported);
        System.out.println("Approved role balance checks passed: " + checks + "; 386 applied, 38 preserved, all modes audited");
    }

    private static void badCatalogChecks(JsonObject exported) {
        JsonObject conflict = exported.deepCopy();
        JsonObject first = conflict.getAsJsonArray("guns").asList().stream().map(JsonElement::getAsJsonObject).filter(g -> "apply".equals(g.get("action").getAsString())).findFirst().orElseThrow();
        JsonObject duplicate = first.deepCopy();
        duplicate.addProperty("gunId", "test:shared_conflict");
        duplicate.getAsJsonObject("target").addProperty("head", 999);
        conflict.getAsJsonArray("guns").add(duplicate);
        reject(() -> RoleBalanceCatalog.parse(conflict), "Shared data policy conflict rejected");
        JsonObject invalid = exported.deepCopy();
        invalid.addProperty("appliedGuns", 1);
        reject(() -> RoleBalanceCatalog.parse(invalid), "Partial catalog rejected");
        var target = RoleBalanceCatalog.parse(exported).byGun().values().stream().filter(g -> g.target() != null).findFirst().orElseThrow().target();
        JsonObject mismatch = JsonParser.parseString("{\"bullet\":{\"bullet_amount\":999}}").getAsJsonObject();
        reject(() -> RoleBalanceTransform.apply(mismatch, target), "Changed pellet count rejected");
        reject(() -> RoleBalanceTransform.retention(target, Double.NaN), "Non-finite distance rejected");
    }
    private static int nativeRpm(JsonObject gun, String modeName) {
        int rpm = gun.has("rpm") ? gun.get("rpm").getAsInt() : 300;
        JsonObject modes = gun.getAsJsonObject("fire_mode_adjust");
        if (modes != null && modes.has(modeName)) {
            JsonObject mode = modes.getAsJsonObject(modeName);
            if (mode.has("rpm")) rpm += mode.get("rpm").getAsInt();
        }
        return rpm <= 0 ? 300 : Math.min(1200, rpm);
    }
    private static double nativeDamage(JsonArray table, double distance) {
        for (JsonElement element : table) {
            JsonObject pair = element.getAsJsonObject();
            JsonElement threshold = pair.get("distance");
            float value = threshold.getAsJsonPrimitive().isString() ? Float.MAX_VALUE : threshold.getAsFloat();
            if (distance < value) return pair.get("damage").getAsFloat();
        }
        return 0;
    }
    private static double nativeShotDamage(JsonArray table, double distance, int pellets) {
        float base = (float) nativeDamage(table, distance);
        float per = base * (1f / pellets); // EntityKineticBullet.applyShotgunDamageSpread.
        double total = 0;
        for (int i = 0; i < pellets; i++) total += per;
        return total;
    }
    private static void reject(Runnable action, String why) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(why);
    }
    private static void near(double actual, double expected, double tolerance, String why) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, why + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
