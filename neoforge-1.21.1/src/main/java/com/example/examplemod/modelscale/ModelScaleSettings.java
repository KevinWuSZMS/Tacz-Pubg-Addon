package com.example.examplemod.modelscale;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Two independent style presets. All scales multiply the authored model's baseline dimensions. */
public record ModelScaleSettings(Map<String, Scale> current, Map<String, Scale> defaults) {
    public static final String PLAYFUL = "playful_world", PIXEL = "pixel_hardcore";
    public static final List<String> STYLES = List.of(PLAYFUL, PIXEL);
    public static final List<String> ITEMS = List.of("helmet_1", "helmet_2", "helmet_3", "vest_1", "vest_2", "vest_3",
            "backpack_1", "backpack_2", "backpack_3", "parachute", "med_kit", "first_aid_kit", "bandage",
            "energy_drink", "painkiller", "adrenaline_syringe", "frag_grenade", "stun_grenade", "smoke_grenade",
            "molotov", "sticky_bomb", "blue_zone_grenade", "c4").stream().map(id -> "tacz_pubg:" + id).toList();
    public static final Scale ONE = new Scale(1, 1);
    public static final double HELMET_WORN_SCALE = 3.1;
    private static final Gson JSON = new Gson();
    public record Scale(double dropped, double worn) {
        public Scale {
            if (!valid(dropped) || !valid(worn)) throw new IllegalArgumentException("Model scale must be between 0.1 and 5");
        }
        private static boolean valid(double value) { return Double.isFinite(value) && value >= .1 && value <= 5; }
    }
    public ModelScaleSettings {
        current = checked(current); defaults = checked(defaults);
    }
    public static ModelScaleSettings initial() { return new ModelScaleSettings(Map.of(), Map.of()); }
    public static boolean supported(String id) { return ITEMS.contains(id); }
    public static boolean wearable(String id) {
        return id.equals("tacz_pubg:parachute") || id.startsWith("tacz_pubg:helmet_")
                || id.startsWith("tacz_pubg:vest_") || id.startsWith("tacz_pubg:backpack_");
    }
    private static String key(String style, String item) {
        if (!STYLES.contains(style) || !supported(item)) throw new IllegalArgumentException("Unknown model style or item");
        return style + "/" + item;
    }
    private static Map<String, Scale> checked(Map<String, Scale> values) {
        Objects.requireNonNull(values);
        if (values.size() > ITEMS.size() * STYLES.size()) throw new IllegalArgumentException("Too many model scale entries");
        var result = new LinkedHashMap<String, Scale>();
        values.forEach((key, value) -> {
            int split = key.indexOf('/');
            if (split <= 0 || !key(key.substring(0, split), key.substring(split + 1)).equals(key))
                throw new IllegalArgumentException("Unknown model scale entry");
            if (!Objects.requireNonNull(value).equals(initialScale(key.substring(0, split), key.substring(split + 1)))) result.put(key, value);
        });
        return Map.copyOf(result);
    }
    public static Scale initialScale(String style, String item) {
        return PIXEL.equals(style) && item.startsWith("tacz_pubg:helmet_") ? new Scale(1, HELMET_WORN_SCALE) : ONE;
    }
    public Scale get(String style, String item) { return current.getOrDefault(key(style, item), initialScale(style, item)); }
    /** One-time application of the requested helmet fitting, preserving all dropped and other-item values. */
    public ModelScaleSettings alignHelmetWornScales() {
        var next = this;
        String style = PIXEL;
        for (int tier = 1; tier <= 3; tier++) {
            String id = "tacz_pubg:helmet_" + tier;
            next = next.with(style, id, next.get(style, id).dropped(), HELMET_WORN_SCALE);
        }
        return next;
    }
    public ModelScaleSettings with(String style, String item, double dropped, double worn) {
        var copy = new LinkedHashMap<>(current);
        copy.put(key(style, item), new Scale(dropped, worn));
        return new ModelScaleSettings(copy, defaults);
    }
    /** Saving a default changes only that style; it also saves the current draft's values. */
    public ModelScaleSettings setDefault(String style) {
        var copy = new LinkedHashMap<>(defaults);
        replaceStyle(copy, current, style);
        return new ModelScaleSettings(current, copy);
    }
    public ModelScaleSettings restoreDefault(String style) {
        var copy = new LinkedHashMap<>(current);
        replaceStyle(copy, defaults, style);
        return new ModelScaleSettings(copy, defaults);
    }
    public ModelScaleSettings restoreInitial(String style) {
        var copy = new LinkedHashMap<>(current);
        replaceStyle(copy, Map.of(), style);
        return new ModelScaleSettings(copy, defaults);
    }
    private static void replaceStyle(Map<String, Scale> destination, Map<String, Scale> source, String style) {
        if (!STYLES.contains(style)) throw new IllegalArgumentException("Unknown model style");
        destination.keySet().removeIf(key -> key.startsWith(style + "/"));
        source.forEach((key, value) -> { if (key.startsWith(style + "/")) destination.put(key, value); });
    }
    public String toJson() { return JSON.toJson(this); }
    public static ModelScaleSettings parse(String text) {
        JsonObject object = JsonParser.parseString(text).getAsJsonObject();
        return new ModelScaleSettings(read(object.get("current")), read(object.get("defaults")));
    }
    private static Map<String, Scale> read(JsonElement element) {
        if (element == null || element.isJsonNull()) return Map.of();
        var result = new LinkedHashMap<String, Scale>();
        for (var entry : element.getAsJsonObject().entrySet()) {
            var value = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new Scale(value.get("dropped").getAsDouble(), value.get("worn").getAsDouble()));
        }
        return result;
    }
}
