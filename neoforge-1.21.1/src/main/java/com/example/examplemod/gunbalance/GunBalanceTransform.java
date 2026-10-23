package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Applies the reviewed analysis factors to a fresh TaCZ gun-data JSON, never to a live cache. */
public final class GunBalanceTransform {
    private static final Map<String, Double> DEFAULT_INACCURACY = Map.of(
            "stand", 5.0, "move", 5.75, "sneak", 3.5, "lie", 2.5, "aim", 0.15);

    private GunBalanceTransform() {}

    /** The default branch returns the unmodified source, so restoring cannot invert rounded values. */
    public static JsonObject applyProfile(String profile, JsonObject source, double damageFactor, double controlFactor) {
        return switch (profile) {
            case "default" -> source;
            case "researched" -> apply(source, damageFactor, controlFactor);
            default -> throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.gun_balance_transform.unknown_gun_balance_profile") + profile);
        };
    }

    public static JsonObject apply(JsonObject source, double damageFactor, double controlFactor) {
        if (!Double.isFinite(damageFactor) || damageFactor <= 0
                || !Double.isFinite(controlFactor) || controlFactor <= 0) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.gun_balance_transform.gun_balance_factors_must_be_finite_and_positive"));
        }
        JsonObject result = source.deepCopy();
        JsonObject bullet = object(result, "bullet");
        bullet.addProperty("damage", number(bullet, "damage", 5.0) * damageFactor);
        JsonObject extra = optionalObject(bullet, "extra_damage");
        if (extra != null) {
            JsonArray adjustments = optionalArray(extra, "damage_adjust");
            if (adjustments != null) for (JsonElement element : adjustments) {
                if (element.isJsonObject()) scaleExisting(element.getAsJsonObject(), "damage", damageFactor);
            }
        }
        JsonObject recoil = optionalObject(result, "recoil");
        if (recoil != null) for (String axis : new String[] {"pitch", "yaw"}) {
            JsonArray keyframes = optionalArray(recoil, axis);
            if (keyframes == null) continue;
            for (JsonElement element : keyframes) {
                if (!element.isJsonObject()) continue;
                JsonObject frame = element.getAsJsonObject();
                JsonArray values = optionalArray(frame, "value");
                if (values == null) continue;
                for (int i = 0; i < values.size(); i++) {
                    values.set(i, new com.google.gson.JsonPrimitive(values.get(i).getAsDouble() * controlFactor));
                }
            }
        }
        JsonObject inaccuracy = object(result, "inaccuracy");
        DEFAULT_INACCURACY.forEach((stance, fallback) -> inaccuracy.addProperty(
                stance, number(inaccuracy, stance, fallback) * controlFactor));
        JsonObject modes = optionalObject(result, "fire_mode_adjust");
        if (modes != null) for (JsonElement element : modes.asMap().values()) {
            if (!element.isJsonObject()) continue;
            JsonObject mode = element.getAsJsonObject();
            scaleExisting(mode, "damage", damageFactor);
            scaleExisting(mode, "aim_inaccuracy", controlFactor);
            scaleExisting(mode, "other_inaccuracy", controlFactor);
        }
        return result;
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
    }

    private static void scaleExisting(JsonObject object, String key, double factor) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            object.addProperty(key, object.get(key).getAsDouble() * factor);
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonObject result = optionalObject(parent, key);
        if (result != null) return result;
        result = new JsonObject();
        parent.add(key, result);
        return result;
    }

    private static JsonObject optionalObject(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray optionalArray(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }
}
