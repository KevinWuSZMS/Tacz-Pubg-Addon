package com.example.examplemod.keybindings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercises upgrades on independent documents; never reads or writes a user's running game config. */
public final class BindingProfileMigrationChecks {
    private static int checks;
    public static void main(String[] args) { run(); }

    public static void run() {
        JsonObject original = JsonParser.parseString("""
                {"version":1,"customMetadata":"preserve","profiles":{
                  "GENERAL":{
                    "bindings":{
                      "key.sneak":{"keys":["ShiftRight"],"mode":"CHORD"},
                      "key.sprint":{"keys":["ControlAny","G"],"mode":"CHORD"},
                      "other.action":{"keys":["G","Mouse4"],"mode":"ANY"}},
                    "defaults":{
                      "key.sneak":{"keys":["ShiftLeft"],"mode":"CHORD"},
                      "key.sprint":{"keys":["ControlLeft"],"mode":"CHORD"}},
                    "behavior":{"toggleCrouch":false,"toggleSprint":true,"autoJump":true},
                    "behaviorDefaults":{"toggleCrouch":false,"toggleSprint":false,"autoJump":false}},
                  "MATCH":{
                    "bindings":{
                      "key.sneak":{"keys":["C"],"mode":"CHORD"},
                      "key.sprint":{"keys":["ShiftLeft"],"mode":"CHORD"},
                      "key.tacz.crawl.desc":{"keys":["Z"],"mode":"CHORD"},
                      "other.action":{"keys":["AltAny","Mouse2"],"mode":"CHORD"}},
                    "defaults":{
                      "key.sneak":{"keys":["C"],"mode":"CHORD"},
                      "key.sprint":{"keys":["ShiftLeft"],"mode":"CHORD"},
                      "key.tacz.crawl.desc":{"keys":["Z"],"mode":"CHORD"}},
                    "behavior":{"toggleCrouch":true,"toggleSprint":false,"autoJump":false},
                    "behaviorDefaults":{"toggleCrouch":true,"toggleSprint":false,"autoJump":false}}
                }}
                """).getAsJsonObject();
        JsonObject snapshot = original.deepCopy();
        JsonObject updated = BindingProfileMigration.upgrade(original);
        check(original.equals(snapshot), "Upgrade never mutates the original backup document");
        check(updated.get("version").getAsInt() == BindingProfileMigration.VERSION, "Document uses current version");
        check(updated.get("behavior").equals(profile(original, "GENERAL").get("behavior")), "Global settings come from General, not Match");
        check(updated.get("behaviorDefaults").equals(profile(original, "GENERAL").get("behaviorDefaults")), "Global default settings also come from General");
        for (String name : new String[]{"GENERAL", "MATCH"}) {
            check(!profile(updated, name).has("behavior") && !profile(updated, name).has("behaviorDefaults"), "Profiles no longer own behavior options");
        }
        for (String section : new String[]{"bindings", "defaults"}) {
            for (String id : new String[]{"key.sneak", "key.sprint"}) {
                check(keys(updated, "MATCH", section).get(id).equals(keys(original, "GENERAL", section).get(id)), "Native movement mapping/default is restored from General: " + id);
                check(keys(updated, "GENERAL", section).get(id).equals(keys(original, "GENERAL", section).get(id)), "General native controls stay exact: " + id);
            }
            check(keys(updated, "MATCH", section).getAsJsonObject("key.tacz.crawl.desc").getAsJsonArray("keys").isEmpty(), "Native TaCZ crawl becomes unbound in Match");
            BindingProfileMigration.MATCH_ACTION_DEFAULTS.forEach((id, key) -> {
                check(keys(updated, "GENERAL", section).getAsJsonObject(id).getAsJsonArray("keys").isEmpty(), "New match action starts unbound in General: " + id);
                JsonObject matchBinding = keys(updated, "MATCH", section).getAsJsonObject(id);
                check(matchBinding.getAsJsonArray("keys").size() == 1 && matchBinding.getAsJsonArray("keys").get(0).getAsString().equals(key), "New match action has its designated default: " + id);
            });
        }
        for (String name : new String[]{"GENERAL", "MATCH"}) {
            check(keys(updated, name, "bindings").get("other.action").equals(keys(original, name, "bindings").get("other.action")), "Unrelated chords/OR alternatives remain exact");
        }
        check(updated.get("customMetadata").equals(original.get("customMetadata")), "Unknown metadata is retained during migration");
        keys(updated, "MATCH", "bindings").add("key.tacz_pubg.match_sprint", JsonParser.parseString("{\"keys\":[\"Mouse4\",\"G\"],\"mode\":\"ANY\"}"));
        updated.getAsJsonObject("behavior").addProperty("toggleCrouch", true);
        check(BindingProfileMigration.upgrade(updated).equals(updated), "Current version upgrades are idempotent and retain subsequent user changes");
        JsonObject previous = updated.deepCopy(); previous.addProperty("version", 2);
        for (String section : new String[]{"bindings", "defaults"}) {
            keys(previous, "MATCH", section).add("key.drop", JsonParser.parseString("{\"keys\":[\"Q\"],\"mode\":\"CHORD\"}"));
            keys(previous, "MATCH", section).add("key.inventory", JsonParser.parseString("{\"keys\":[\"E\"],\"mode\":\"CHORD\"}"));
            keys(previous, "MATCH", section).remove("key.tacz_pubg.lean_left");
            keys(previous, "GENERAL", section).remove("key.tacz_pubg.lean_left");
        }
        JsonObject peek = BindingProfileMigration.upgrade(previous);
        check(keys(peek, "MATCH", "bindings").getAsJsonObject("key.drop").getAsJsonArray("keys").get(0).getAsString().equals("Backspace"), "Match drop moves to Backspace");
        check(keys(peek, "MATCH", "bindings").getAsJsonObject("key.inventory").getAsJsonArray("keys").get(0).getAsString().equals("Tab"), "Match stock inventory moves to Tab");
        check(keys(peek, "MATCH", "bindings").getAsJsonObject("key.inventory").getAsJsonArray("keys").get(1).getAsString().equals("I")
                && keys(peek, "MATCH", "bindings").getAsJsonObject("key.inventory").get("mode").getAsString().equals("ANY"), "Tab or I independently opens inventory");
        check(keys(peek, "MATCH", "bindings").getAsJsonObject("key.tacz_pubg.lean_left").getAsJsonArray("keys").get(0).getAsString().equals("Q"), "Version two receives Q lean");
        check(keys(peek, "GENERAL", "bindings").getAsJsonObject("key.tacz_pubg.lean_left").getAsJsonArray("keys").isEmpty(), "General lean remains unbound");
        previous.addProperty("version", 2);
        keys(previous, "MATCH", "bindings").add("key.inventory", JsonParser.parseString("{\"keys\":[\"ControlAny\",\"E\"],\"mode\":\"CHORD\"}"));
        check(keys(BindingProfileMigration.upgrade(previous), "MATCH", "bindings").get("key.inventory").equals(keys(previous, "MATCH", "bindings").get("key.inventory")), "Personal inventory chord is preserved");
        JsonObject future = updated.deepCopy(); future.addProperty("version", 999);
        boolean rejected = false;
        try { BindingProfileMigration.upgrade(future); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Unknown future versions are not silently rewritten");
        System.out.println("BindingProfileMigrationChecks: " + checks + " document migration checks passed; no game config touched.");
    }

    private static JsonObject profile(JsonObject document, String profile) { return document.getAsJsonObject("profiles").getAsJsonObject(profile); }
    private static JsonObject keys(JsonObject document, String profile, String section) { return profile(document, profile).getAsJsonObject(section); }
    private static void check(boolean condition, String description) { checks++; if (!condition) throw new AssertionError(description); }
}
