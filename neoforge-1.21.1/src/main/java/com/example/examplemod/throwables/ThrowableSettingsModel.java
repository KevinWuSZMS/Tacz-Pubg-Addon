package com.example.examplemod.throwables;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Saved overrides, independent of loot probabilities and round membership. */
public final class ThrowableSettingsModel {
    public static final int MAX_BYTES = 24_000;
    private ThrowableSettingsModel() {}

    public static JsonObject parse(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Throwable settings exceed 24 KiB");
        var value = strictJson(text);
        if (!value.isJsonObject()) throw new IllegalArgumentException("Expected throwable settings object");
        JsonObject result = value.getAsJsonObject();
        if (result.size() > 512) throw new IllegalArgumentException("Too many throwable settings");
        for (var entry : result.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !entry.getValue().isJsonObject())
                throw new IllegalArgumentException("Invalid throwable entry: " + entry.getKey());
            validate(entry.getValue(), 0);
            if (entry.getKey().startsWith("tacz_pubg:")) {
                boolean exists = java.util.Arrays.stream(ThrowableKind.values()).anyMatch(kind -> entry.getKey().equals("tacz_pubg:" + kind.id));
                if (!exists) throw new IllegalArgumentException("Unknown PUBG throwable: " + entry.getKey());
                var profile = new Profile(entry.getValue().getAsJsonObject());
                profile.validate();
            }
        }
        return result.deepCopy();
    }
    private static JsonElement strictJson(String text) {
        try (var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Unexpected trailing JSON");
            return value;
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid throwable settings JSON", failure); }
    }
    private static JsonElement read(com.google.gson.stream.JsonReader reader, int depth) throws java.io.IOException {
        if (depth > 16) throw new IllegalArgumentException("Throwable properties are nested too deeply");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IllegalArgumentException("Duplicate throwable property: " + name);
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new com.google.gson.JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new com.google.gson.JsonPrimitive(reader.nextString());
            case NUMBER -> new com.google.gson.JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new com.google.gson.JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield com.google.gson.JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Expected JSON value");
        };
    }
    /** Hide settings that have no consumer for this item's actual gameplay implementation. */
    public static boolean applicable(ThrowableKind kind, String field) {
        return switch (field) {
            case "radius" -> kind.explosive() || kind == ThrowableKind.STUN || kind == ThrowableKind.MOLOTOV || kind == ThrowableKind.BLUE_ZONE;
            case "damage", "terrain_power", "damage_curve_p", "damage_curve_q", "prone_damage_multiplier", "destroy_blocks" -> kind.explosive();
            case "fire_damage", "burn_ticks" -> kind == ThrowableKind.MOLOTOV;
            case "blue_zone_damage" -> kind == ThrowableKind.BLUE_ZONE;
            case "field_growth_ticks", "field_fade_ticks", "damage_interval_ticks" -> kind == ThrowableKind.MOLOTOV || kind == ThrowableKind.BLUE_ZONE;
            case "field_ticks" -> kind == ThrowableKind.SMOKE || kind == ThrowableKind.MOLOTOV || kind == ThrowableKind.BLUE_ZONE;
            case "fuse_ticks" -> kind != ThrowableKind.MOLOTOV;
            case "cooked_fuse_ticks", "cookable" -> kind != ThrowableKind.C4;
            case "deployment_ticks", "placement_reach" -> kind == ThrowableKind.C4;
            default -> true;
        };
    }
    private static void validate(JsonElement element, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Throwable properties are nested too deeply");
        if (element.isJsonObject()) for (var entry : element.getAsJsonObject().entrySet()) validate(entry.getValue(), depth + 1);
        else if (element.isJsonArray()) for (var value : element.getAsJsonArray()) validate(value, depth + 1);
        else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                && !Double.isFinite(element.getAsDouble())) throw new IllegalArgumentException("Expected finite property value");
    }
    public static JsonObject merge(JsonObject base, JsonObject override) {
        JsonObject result = base.deepCopy();
        for (var entry : override.entrySet()) {
            JsonElement previous = result.get(entry.getKey()), value = entry.getValue();
            result.add(entry.getKey(), previous != null && previous.isJsonObject() && value.isJsonObject()
                    ? merge(previous.getAsJsonObject(), value.getAsJsonObject()) : value.deepCopy());
        }
        return result;
    }
    public static JsonObject defaults(ThrowableKind kind) {
        JsonObject data = new JsonObject();
        data.addProperty("fuse_ticks", kind == ThrowableKind.STUN ? 60 : kind == ThrowableKind.SMOKE ? 40 : kind.fuse);
        data.addProperty("cooked_fuse_ticks", kind == ThrowableKind.STUN ? 60 : kind.fuse);
        data.addProperty("field_ticks", kind == ThrowableKind.SMOKE ? 600 : kind.fieldTicks);
        data.addProperty("prepare_ticks", 16); data.addProperty("cooldown_ticks", 40);
        data.addProperty("stack_size", 16); data.addProperty("deployment_ticks", 80);
        data.addProperty("max_lifetime_ticks", 1200);
        data.addProperty("radius", kind == ThrowableKind.STUN ? 32 : kind.radius);
        data.addProperty("damage", kind.damage); data.addProperty("gravity", .05);
        data.addProperty("bounce", .55); data.addProperty("hit_damage", 0);
        data.addProperty("speed", kind == ThrowableKind.SMOKE || kind == ThrowableKind.STUN ? 1.1 : 1.3);
        data.addProperty("low_speed", .55); data.addProperty("dropped_speed", .05);
        data.addProperty("pitch_offset", -10); data.addProperty("placement_reach", 3);
        data.addProperty("fire_damage", 1); data.addProperty("blue_zone_damage", 2);
        data.addProperty("field_growth_ticks", kind == ThrowableKind.BLUE_ZONE ? 100 : 50);
        data.addProperty("field_fade_ticks", kind == ThrowableKind.BLUE_ZONE ? 100 : 60);
        data.addProperty("burn_ticks", 80); data.addProperty("damage_interval_ticks", 20);
        data.addProperty("terrain_power", kind == ThrowableKind.C4 ? 6 : 3);
        data.addProperty("damage_curve_p", 2); data.addProperty("damage_curve_q", kind == ThrowableKind.C4 ? 2 : 1.5);
        data.addProperty("prone_damage_multiplier", .8);
        data.addProperty("cookable", kind == ThrowableKind.FRAG || kind == ThrowableKind.STUN || kind == ThrowableKind.SMOKE);
        data.addProperty("destroy_blocks", true);
        return data;
    }
    public static final class Profile {
        private final JsonObject data;
        public Profile(JsonObject data) { this.data = data.deepCopy(); }
        public JsonObject json() { return data.deepCopy(); }
        public double number(String key, double fallback) { return data.has(key) ? data.get(key).getAsDouble() : fallback; }
        public int integer(String key, int fallback) { return data.has(key) ? data.get(key).getAsInt() : fallback; }
        public boolean flag(String key, boolean fallback) { return data.has(key) ? data.get(key).getAsBoolean() : fallback; }
        public int fuseTicks() { return integer("fuse_ticks", 100); }
        public int cookedFuseTicks() { return integer("cooked_fuse_ticks", fuseTicks()); }
        public int fieldTicks() { return integer("field_ticks", 0); }
        public int prepareTicks() { return integer("prepare_ticks", 16); }
        public int cooldownTicks() { return integer("cooldown_ticks", 40); }
        public int stackSize() { return integer("stack_size", 16); }
        public int deploymentTicks() { return integer("deployment_ticks", 80); }
        public int maxLifetimeTicks() { return integer("max_lifetime_ticks", 1200); }
        public double radius() { return number("radius", 8); }
        public double damage() { return number("damage", 40); }
        public double gravity() { return number("gravity", .05); }
        public double bounce() { return number("bounce", .55); }
        public double hitDamage() { return number("hit_damage", 0); }
        public double speed() { return number("speed", 1.3); }
        public double lowSpeed() { return number("low_speed", .55); }
        public double droppedSpeed() { return number("dropped_speed", .05); }
        public double pitchOffset() { return number("pitch_offset", -10); }
        public double placementReach() { return number("placement_reach", 3); }
        public double fireDamage() { return number("fire_damage", 1); }
        public double blueZoneDamage() { return number("blue_zone_damage", 1); }
        public double fieldGrowthTicks() { return number("field_growth_ticks", 50); }
        public double fieldFadeTicks() { return number("field_fade_ticks", 60); }
        public boolean cookable() { return flag("cookable", false); }
        public boolean destroyBlocks() { return flag("destroy_blocks", true); }
        private void validate() {
            JsonObject known = defaults(ThrowableKind.FRAG);
            for (var entry : data.entrySet()) {
                String key = entry.getKey(); JsonElement value = entry.getValue();
                if (key.equals("native_data")) {
                    if (!value.isJsonObject()) throw new IllegalArgumentException("native_data must be an object");
                    JsonObject nativeData = value.getAsJsonObject();
                    for (String duplicate : new String[]{"prepare_time", "cooldown", "stack_size", "cookable", "initial_speed"})
                        if (nativeData.has(duplicate)) throw new IllegalArgumentException("Use the PUBG profile field instead of native_data." + duplicate);
                    if (nativeData.has("entity")) {
                        if (!nativeData.get("entity").isJsonObject()) throw new IllegalArgumentException("native_data.entity must be an object");
                        for (String duplicate : new String[]{"life_time", "gravity", "bounce_factor", "hit_damage"})
                            if (nativeData.getAsJsonObject("entity").has(duplicate)) throw new IllegalArgumentException("Duplicate entity field: " + duplicate);
                    }
                    if (nativeData.has("stun")) {
                        if (!nativeData.get("stun").isJsonObject()) throw new IllegalArgumentException("native_data.stun must be an object");
                        if (nativeData.getAsJsonObject("stun").has("radius")) throw new IllegalArgumentException("Use radius instead of native_data.stun.radius");
                    }
                    continue;
                }
                if (!known.has(key)) throw new IllegalArgumentException("Unknown PUBG property: " + key);
                boolean booleanProperty = known.get(key).getAsJsonPrimitive().isBoolean();
                if (!value.isJsonPrimitive() || booleanProperty && !value.getAsJsonPrimitive().isBoolean()
                        || !booleanProperty && !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException(key + (booleanProperty ? ": expected boolean" : ": expected number"));
            }
            for (var entry : data.entrySet()) if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                double value = entry.getValue().getAsDouble();
                double min = entry.getKey().equals("pitch_offset") ? -180 : 0;
                double max = entry.getKey().equals("stack_size") ? 99 : entry.getKey().equals("pitch_offset") ? 180
                        : entry.getKey().contains("radius") || entry.getKey().equals("placement_reach") ? 256 : 72000;
                if (value < min || value > max) throw new IllegalArgumentException(entry.getKey() + ": " + min + ".." + max);
                if (entry.getKey().endsWith("ticks") || entry.getKey().equals("stack_size"))
                    if (value != Math.rint(value)) throw new IllegalArgumentException(entry.getKey() + ": expected integer");
            }
            if (data.has("stack_size") && stackSize() < 1) throw new IllegalArgumentException("stack_size: 1..99");
            if (data.has("max_lifetime_ticks") && maxLifetimeTicks() < 1) throw new IllegalArgumentException("max_lifetime_ticks must be positive");
        }
    }
}
