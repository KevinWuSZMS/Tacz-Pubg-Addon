package com.example.examplemod.throwables;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;

/** Saved property validation, immutable hot-path caching and lossless custom subtype overlays. */
public final class ThrowableSettingsChecks {
    private static int checks;
    public static void main(String[] args) {
        for (var kind : ThrowableKind.values()) {
            JsonObject all = new JsonObject(); all.add("tacz_pubg:" + kind.id, ThrowableSettingsModel.defaults(kind));
            check(ThrowableSettingsModel.parse(all.toString()).equals(all), "All factory properties validate: " + kind.id);
        }
        for (String properties : new String[]{"{\"gravity\":false}", "{\"gravity\":\"0.05\"}", "{\"gravity\":null}",
                "{\"gravity\":{}}", "{\"cookable\":0}", "{\"cookable\":\"true\"}", "{\"fuse_ticks\":1.5}",
                "{\"stack_size\":0}", "{\"stack_size\":100}", "{\"max_lifetime_ticks\":0}",
                "{\"radius\":257}", "{\"pitch_offset\":-181}", "{\"unknown_field\":1}",
                "{\"native_data\":[]}", "{\"native_data\":{\"entity\":false}}", "{\"native_data\":{\"stun\":false}}",
                "{\"native_data\":{\"entity\":{\"gravity\":0.2}}}", "{\"native_data\":{\"prepare_time\":30}}",
                "{\"native_data\":{\"stun\":{\"radius\":12}}}"})
            rejects(() -> ThrowableSettingsModel.parse("{\"tacz_pubg:frag_grenade\":" + properties + "}"), "Malformed or duplicate gameplay property rejected");
        rejects(() -> ThrowableSettingsModel.parse("{\"tacz_pubg:not_a_real_item\":{}}"), "Unknown PUBG profile cannot silently do nothing");
        rejects(() -> ThrowableSettingsModel.parse("{\"tacz_pubg:frag_grenade\":{\"gravity\":0.1,\"gravity\":0.2}}"), "Duplicate JSON keys cannot silently change precedence");
        rejects(() -> ThrowableSettingsModel.parse("{} {}"), "Trailing JSON is rejected");
        rejects(() -> ThrowableSettingsModel.parse("{\"lrtest:grenade\":{\"data\":{\"force\":1e9999}}}"), "Nonfinite native numbers are rejected");
        var valid = ThrowableSettingsModel.parse("{\"tacz_pubg:stun_grenade\":{\"radius\":0,\"pitch_offset\":-180,\"stack_size\":99,\"cookable\":true}}");
        check(valid.has("tacz_pubg:stun_grenade"), "Inclusive numeric limits retain intended zero and negative pitch values");
        check(!ThrowableSettingsModel.applicable(ThrowableKind.SMOKE, "radius"), "Smoke does not expose a fake radius unsupported by LR's native smoke");
        check(!ThrowableSettingsModel.applicable(ThrowableKind.STUN, "damage") && ThrowableSettingsModel.applicable(ThrowableKind.STUN, "radius"), "Stun exposes actual radius and no explosive damage");
        check(ThrowableSettingsModel.applicable(ThrowableKind.C4, "deployment_ticks") && !ThrowableSettingsModel.applicable(ThrowableKind.FRAG, "deployment_ticks"), "Placement-only settings are only shown for C4");

        // An unknown LR subtype may use custom serializers, record values, filter objects and
        // particle command strings; changing a number must leave every other value untouched.
        JsonObject nativeSource = JsonParser.parseString("""
                {"type":"lrtest:custom_cloud","name":"item.lrtest.cloud","base_item":"lrtactical:throwable","data":{
                  "entity":{"gravity":0.05,"tail_particles":["minecraft:dust{color:[1.0f,0.2f,0.3f],scale:1.5f}"]},
                  "cloud":{"radius":5.5,"radius_per_tick":-0.02,"effects":[{"id":"minecraft:slowness","amplifier":2,"duration":400}]},
                  "target_filter":{"type":"lrtest:tag","value":"lrtest:targets"},"future_field":{"nested":[true,{"x":"retain"}]}}}
                """).getAsJsonObject();
        JsonObject overlay = JsonParser.parseString("{\"data\":{\"cloud\":{\"radius\":8}}}").getAsJsonObject();
        JsonObject merged = ThrowableSettingsModel.merge(nativeSource, overlay);
        check(merged.getAsJsonObject("data").getAsJsonObject("cloud").get("radius").getAsDouble() == 8, "Requested native property changes");
        for (String key : new String[]{"entity", "target_filter", "future_field"})
            check(merged.getAsJsonObject("data").get(key).equals(nativeSource.getAsJsonObject("data").get(key)), "Custom native values survive untouched: " + key);
        check(merged.getAsJsonObject("data").getAsJsonObject("cloud").get("effects").equals(nativeSource.getAsJsonObject("data").getAsJsonObject("cloud").get("effects")), "Custom effect records survive numeric edits");
        check(nativeSource.getAsJsonObject("data").getAsJsonObject("cloud").get("radius").getAsDouble() == 5.5, "Merging never mutates the registered LR base source");
        JsonObject settings = new JsonObject(); settings.add("lrtest:cloud", overlay);
        check(ThrowableSettingsModel.parse(settings.toString()).equals(settings), "Arbitrary native subtype field paths remain supported");

        ThrowableSettingsService.clearClient();
        var first = ThrowableSettingsService.profile(ThrowableKind.FRAG);
        check(first == ThrowableSettingsService.profile(ThrowableKind.FRAG), "Repeated per-tick reads reuse the immutable profile object");
        JsonObject firstCopy = first.json(); firstCopy.addProperty("gravity", 900);
        check(first.gravity() == .05, "A caller cannot mutate a cached profile through its JSON copy");
        ThrowableSettingsService.acceptClient(ThrowableSettingsModel.parse("{\"tacz_pubg:frag_grenade\":{\"gravity\":0.2}}"));
        var next = ThrowableSettingsService.profile(ThrowableKind.FRAG);
        check(next != first && next.gravity() == .2 && first.gravity() == .05, "New synchronized state invalidates cache without changing old snapshots");
        ThrowableSettingsService.clearClient();

        CompoundTag tag = new CompoundTag(); tag.putInt("Schema", 1); tag.putLong("Revision", 9);
        tag.putString("Settings", "{\"tacz_pubg:frag_grenade\":{\"gravity\":0.1}}");
        var loaded = ThrowableSettingsSavedData.load(tag, RegistryAccess.EMPTY);
        check(loaded.revision() == 9 && loaded.error().isEmpty(), "Saved revision and valid profile load without a world");
        check(loaded.save(new CompoundTag(), RegistryAccess.EMPTY).equals(tag), "Valid source data round trips");
        tag.putString("Settings", "{\"tacz_pubg:frag_grenade\":{\"gravity\":false}}");
        var protectedData = ThrowableSettingsSavedData.load(tag, RegistryAccess.EMPTY);
        check(!protectedData.error().isEmpty(), "Malformed old property types protect the save");
        check(protectedData.save(new CompoundTag(), RegistryAccess.EMPTY).equals(tag), "Malformed original data is preserved exactly");
        System.out.println("ThrowableSettingsChecks: " + checks + " checks passed; no game launched.");
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
