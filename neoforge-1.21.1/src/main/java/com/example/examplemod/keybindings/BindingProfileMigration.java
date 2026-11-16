package com.example.examplemod.keybindings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;

/** Pure document migration: preserves general controls and separates the match locomotion actions. */
public final class BindingProfileMigration {
    public static final int VERSION = 3;
    public static final Map<String, String> MATCH_ACTION_DEFAULTS = Map.of(
            "key.tacz_pubg.stand", "Space", "key.tacz_pubg.match_sprint", "ShiftLeft",
            "key.tacz_pubg.match_crouch", "C", "key.tacz_pubg.match_prone", "Z",
            "key.tacz_pubg.match_walk", "ControlLeft",
            "key.tacz_pubg.lean_left", "Q", "key.tacz_pubg.lean_right", "E");

    private BindingProfileMigration() {}

    public static JsonObject upgrade(JsonObject source) {
        int version = source.has("version") ? source.get("version").getAsInt() : 0;
        if (version < 1 || version > VERSION) throw new IllegalArgumentException("Unknown keybinding profile version: " + version);
        JsonObject result = source.deepCopy();
        if (version == VERSION) return result;
        if (version == 2) return addLean(result);
        JsonObject profiles = result.getAsJsonObject("profiles");
        JsonObject general = profiles.getAsJsonObject("GENERAL"), match = profiles.getAsJsonObject("MATCH");
        if (general == null || match == null) throw new IllegalArgumentException("Missing keybinding profile");
        result.add("behavior", general.getAsJsonObject("behavior").deepCopy());
        result.add("behaviorDefaults", general.has("behaviorDefaults")
                ? general.getAsJsonObject("behaviorDefaults").deepCopy() : general.getAsJsonObject("behavior").deepCopy());
        for (String section : new String[]{"bindings", "defaults"}) {
            JsonObject generalKeys = general.getAsJsonObject(section), matchKeys = match.getAsJsonObject(section);
            if (generalKeys == null || matchKeys == null) throw new IllegalArgumentException("Missing keybinding entries");
            for (String id : new String[]{"key.sneak", "key.sprint"}) {
                if (generalKeys.has(id)) matchKeys.add(id, generalKeys.get(id).deepCopy());
            }
            matchKeys.add("key.tacz.crawl.desc", binding(null));
            MATCH_ACTION_DEFAULTS.forEach((id, key) -> {
                generalKeys.add(id, binding(null));
                matchKeys.add(id, binding(key));
            });
        }
        for (var profile : profiles.entrySet()) {
            JsonObject value = profile.getValue().getAsJsonObject();
            value.remove("behavior"); value.remove("behaviorDefaults");
        }
        return addLean(result);
    }

    private static JsonObject addLean(JsonObject result) {
        JsonObject profiles = result.getAsJsonObject("profiles");
        JsonObject general = profiles.getAsJsonObject("GENERAL"), match = profiles.getAsJsonObject("MATCH");
        for (String section : new String[]{"bindings", "defaults"}) {
            JsonObject generalKeys = general.getAsJsonObject(section), matchKeys = match.getAsJsonObject(section);
            for (String side : new String[]{"left", "right"}) {
                String id = "key.tacz_pubg.lean_" + side;
                if (!generalKeys.has(id)) generalKeys.add(id, binding(null));
                if (!matchKeys.has(id)) matchKeys.add(id, binding(side.equals("left") ? "Q" : "E"));
            }
            // Only migrate untouched vanilla defaults. Personal replacements and chords stay exact.
            if (binding("Q").equals(matchKeys.get("key.drop"))) matchKeys.add("key.drop", binding("Backspace"));
            if (binding("E").equals(matchKeys.get("key.inventory"))) {
                JsonObject inventory = binding("Tab");
                inventory.getAsJsonArray("keys").add("I");
                inventory.addProperty("mode", "ANY");
                matchKeys.add("key.inventory", inventory);
            }
        }
        result.addProperty("version", VERSION);
        return result;
    }

    private static JsonObject binding(String key) {
        JsonObject result = new JsonObject();
        JsonArray keys = new JsonArray();
        if (key != null) keys.add(key);
        result.add("keys", keys);
        result.addProperty("mode", "CHORD");
        return result;
    }
}
