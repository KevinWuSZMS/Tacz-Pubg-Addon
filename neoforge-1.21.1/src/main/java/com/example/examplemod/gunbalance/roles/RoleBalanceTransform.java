package com.example.examplemod.gunbalance.roles;

import com.example.examplemod.localization.Texts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Native TaCZ JSON transforms for approved gun roles; no rendering or world access. */
public final class RoleBalanceTransform {
    public record Knot(double distance, double retained) {
        public Knot {
            if (!Double.isFinite(distance) || distance <= 0 || !Double.isFinite(retained)
                    || retained < 0 || retained > 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.invalid_damage_envelope_knot"));
        }
    }

    public record Target(double damage10, double head, double aim, double hip, double ads,
            double rpm, double rpmCap, int pellets, List<Knot> envelope) {
        public Target {
            for (double value : new double[] {damage10, head, aim, hip, ads, rpm, rpmCap})
                if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.invalid_role_target"));
            if (pellets < 1 || rpm > rpmCap || envelope == null || envelope.size() != 7)
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.invalid_role_target_population_or_rpm"));
            envelope = List.copyOf(envelope);
            double[] distances = {10, 25, 50, 100, 200, 300, 500};
            double previous = 1;
            for (int i = 0; i < distances.length; i++) {
                Knot point = envelope.get(i);
                if (point.distance() != distances[i] || point.retained() > previous)
                    throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.damage_envelope_must_decrease_through_the_approved_seven_distances"));
                previous = point.retained();
            }
            if (envelope.get(0).retained() != 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.damage10_requires_unit_retention_at_10m"));
        }
    }

    private RoleBalanceTransform() {}

    /** Disabled means the exact source object, including its field order and precision. */
    public static JsonObject applyProfile(boolean experimental, JsonObject raw, Target target) {
        return experimental && target != null ? apply(raw, target) : raw;
    }

    public static JsonObject apply(JsonObject raw, Target target) {
        if (raw == null || target == null) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.missing_gun_data_or_target"));
        JsonObject sourceBullet = optionalObject(raw, "bullet");
        if (sourceBullet == null) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.approved_gun_has_no_bullet_object"));
        double actualPellets = number(sourceBullet, "bullet_amount", 1);
        if (actualPellets != target.pellets())
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.gunpack_pellet_count_changed_since_approval") + actualPellets + " != " + target.pellets());
        JsonObject result = raw.deepCopy();
        JsonObject bullet = result.getAsJsonObject("bullet");
        // Native applyShotgunDamageSpread already divides each projectile by bullet_amount.
        // These JSON fields are whole-shot values; dividing here too would divide twice.
        bullet.addProperty("damage", target.damage10());
        JsonObject extra = object(bullet, "extra_damage");
        extra.addProperty("head_shot_multiplier", target.head());
        extra.add("damage_adjust", damageTable(target));
        result.addProperty("aim_time", target.ads());

        // Preserve posture ratios from the existing recoil/control experiment. AIM/stand are exact targets.
        JsonObject spread = object(result, "inaccuracy");
        double oldStand = number(spread, "stand", 5);
        for (String posture : new String[] {"move", "sneak", "lie"}) {
            double fallback = switch (posture) { case "move" -> 5.75; case "sneak" -> 3.5; default -> 2.5; };
            double ratio = oldStand > 0 ? number(spread, posture, fallback) / oldStand : fallback / 5;
            spread.addProperty(posture, Math.max(0, target.hip() * ratio));
        }
        spread.addProperty("stand", target.hip());
        spread.addProperty("aim", target.aim());

        // TaCZ mode properties are additive. Clear damage/head/spread offsets so semi/burst
        // cannot bypass the same role envelope. All other mode fields (armor, knockback, etc.) survive.
        JsonObject modes = object(result, "fire_mode_adjust");
        Set<String> modeNames = new LinkedHashSet<>(modes.keySet());
        JsonElement fireModes = result.get("fire_mode");
        if (fireModes != null && fireModes.isJsonArray())
            for (JsonElement mode : fireModes.getAsJsonArray()) modeNames.add(mode.getAsString());
        int oldBase = (int) number(result, "rpm", 300);
        int ceiling = quantizedRpmCeiling(Math.min(target.rpm(), target.rpmCap()));
        int newBase = Math.min(validNativeRpm(oldBase), ceiling);
        result.addProperty("rpm", newBase);
        for (String name : modeNames) {
            JsonObject mode = object(modes, name);
            int oldMode = validNativeRpm(oldBase + (int) number(mode, "rpm", 0));
            mode.addProperty("rpm", Math.min(oldMode, ceiling) - newBase);
            for (String key : new String[] {"damage", "head_shot_multiplier", "aim_inaccuracy", "other_inaccuracy"})
                mode.addProperty(key, 0);
        }
        if (modeNames.contains("burst")) {
            JsonObject burst = object(result, "burst_data");
            // Native burst BPM controls inter-shot timing separately from the RPM cache.
            int bpm = (int) number(burst, "bpm", 200);
            burst.addProperty("bpm", Math.min(bpm > 0 ? bpm : 200, ceiling));
            int count = Math.max(1, (int) number(burst, "count", 3));
            // Bound full burst cadence too, without shortening an author's original delay.
            double minimumPeriod = (Math.ceil(60000.0 * count / Math.min(target.rpm(), target.rpmCap())) + 1) / 1000.0;
            burst.addProperty("min_interval", Math.max(number(burst, "min_interval", 1), minimumPeriod));
        }
        return result;
    }

    /** Left-held 5m steps; native damage lookup uses strict distance < threshold. */
    public static JsonArray damageTable(Target target) {
        JsonArray table = new JsonArray();
        double plateauEnd = target.envelope().get(0).distance();
        for (int i = 1; i < target.envelope().size(); i++) {
            if (target.envelope().get(i).retained() != 1) break;
            plateauEnd = target.envelope().get(i).distance();
        }
        // First threshold represents native EFFECTIVE_RANGE. Do not accidentally turn it into 5m.
        addDamage(table, plateauEnd, target.damage10());
        for (double start = plateauEnd; start < 500; start += 5)
            addDamage(table, start + 5, target.damage10() * retention(target, start));
        JsonObject tail = new JsonObject();
        tail.addProperty("distance", "infinite");
        tail.addProperty("damage", target.damage10() * retention(target, 500));
        table.add(tail);
        return table;
    }

    public static double retention(Target target, double distance) {
        if (!Double.isFinite(distance) || distance < 0) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.invalid_distance"));
        List<Knot> points = target.envelope();
        if (distance <= points.get(0).distance()) return points.get(0).retained();
        for (int i = 1; i < points.size(); i++) {
            Knot current = points.get(i);
            if (distance <= current.distance()) {
                Knot previous = points.get(i - 1);
                double q = (distance - previous.distance()) / (current.distance() - previous.distance());
                return previous.retained() + q * (current.retained() - previous.retained());
            }
        }
        return points.get(points.size() - 1).retained();
    }

    private static int validNativeRpm(int rpm) { return rpm > 0 ? Math.min(1200, rpm) : 300; }
    /** Native timers use integer 60000 / RPM milliseconds, which otherwise round a 700 cap up to 705.88. */
    public static int quantizedRpmCeiling(double desired) {
        if (!Double.isFinite(desired) || desired <= 0) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.invalid_rpm_ceiling"));
        return Math.max(1, (int) Math.floor(60000.0 / Math.ceil(60000.0 / Math.min(1200, desired))));
    }
    private static void addDamage(JsonArray table, double distance, double damage) {
        JsonObject entry = new JsonObject();
        entry.addProperty("distance", distance);
        entry.addProperty("damage", damage);
        table.add(entry);
    }
    private static double number(JsonObject parent, String key, double fallback) {
        JsonElement element = parent.get(key);
        double number = element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.role_balance_transform.non_finite_gun_property") + key);
        return number;
    }
    private static JsonObject optionalObject(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }
    private static JsonObject object(JsonObject parent, String key) {
        JsonObject child = optionalObject(parent, key);
        if (child == null) { child = new JsonObject(); parent.add(key, child); }
        return child;
    }
}
