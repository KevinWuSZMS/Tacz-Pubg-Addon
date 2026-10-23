package com.example.examplemod.zones.match;

import com.example.examplemod.zones.match.MatchConfigModel.Phase;
import com.example.examplemod.zones.match.MatchConfigModel.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pure next-match template, bounded codec and in-memory SavedData checks. No server or world starts. */
public final class MatchConfigChecks {
    private static int checks;

    private MatchConfigChecks() {}

    public static void main(String[] args) {
        presetsAndOwnership();
        flowCompatibility();
        dropRuleCompatibility();
        equipmentRuleCompatibility();
        codecStrictness();
        boundsAndTimeCaps();
        templateIsolationAndRevision();
        lastPlayedRuleSelections();
        legacyRosterMigration();
        migrationBackupGate();
        protectedNbt();
        System.out.println("Match config checks passed: " + checks);
    }

    private static void presetsAndOwnership() {
        Settings shortMatch = MatchConfigModel.shortTimingPreset(1);
        check(MatchConfigModel.validate(shortMatch).isEmpty(), "short preset is valid");
        check(shortMatch.phases().size() == 9, "short preset contains nine independently editable phases");
        check(MatchConfigModel.totalPhaseSeconds(shortMatch) == 870, "short phases last 14m30s excluding preparation");
        check(shortMatch.preparationSeconds() == 30 && shortMatch.maxMatchSeconds() == 2400,
                "preparation and combat cap remain independent");
        check(shortMatch.minPlayers() == 1 && shortMatch.maxPlayers() == 20 && shortMatch.teamSize() == 1,
                "default roster constraints are explicit");
        check(shortMatch.blueEnabled() && shortMatch.autoEnd() && shortMatch.spectateEliminated()
                && !shortMatch.friendlyFire(), "default gameplay switches are explicit");
        Settings ranked = MatchConfigModel.rankedTimingPreset(2);
        check(MatchConfigModel.validate(ranked).isEmpty(), "ranked timing reference is valid");
        check(MatchConfigModel.totalPhaseSeconds(ranked) == 1810, "official timing reference is exactly 30m10s");
        check(ranked.flowRules().firstCircleDelaySeconds() == 90 && ranked.phases().getFirst().waitSeconds() == 180,
                "initial delay and first announced-circle wait are independent");
        check(MatchConfigModel.totalPhaseSeconds(MatchConfigModel.defaults(1)) == 1730
                && MatchConfigModel.defaults(1).equals(MatchConfigModel.normalTimingPreset(1)),
                "new rules use the 28m50s normal timing reference");
        check(ranked.phases().getLast().waitSeconds() == 10 && ranked.phases().getLast().shrinkSeconds() == 120,
                "official final phase reference keeps wait and movement separate");
        check(shortMatch.phases().getFirst().radiusRatio() == ranked.phases().getFirst().radiusRatio()
                && shortMatch.phases().getFirst().damagePerSecond() == ranked.phases().getFirst().damagePerSecond(),
                "timing reference does not silently copy PUBG radius or damage units");
        check(MatchConfigModel.parse(MatchConfigModel.toJson(shortMatch)).equals(shortMatch), "short preset JSON round trip");
        check(MatchConfigModel.parse(MatchConfigModel.toJson(ranked)).equals(ranked), "ranked preset JSON round trip");
        ArrayList<Phase> authored = new ArrayList<>(shortMatch.phases());
        Settings owned = phases(shortMatch, authored);
        authored.clear();
        check(owned.phases().size() == 9, "constructor copies mutable phase source");
        rejects(() -> owned.phases().clear(), "phase snapshot is immutable");
        check(!MatchConfigModel.validate(null).isEmpty(), "missing settings produce a validation error");
    }

    private static void flowCompatibility() {
        for (int wait : new int[]{0, 30, 120, 270}) {
            Settings old = new Settings(1, "old", 1, 2, 1, 30, 1000, 60, true, false, true, false,
                    List.of(new Phase(wait, 100, .65, .2, true), new Phase(20, 20, .6, .3, false)));
            JsonObject root = JsonParser.parseString(MatchConfigModel.toJson(old)).getAsJsonObject();
            root.remove("flowRules");
            Settings migrated = MatchConfigModel.parse(root.toString());
            check(migrated.flowRules().firstCircleDelaySeconds() == Math.min(90, wait)
                    && migrated.phases().getFirst().waitSeconds() == wait - Math.min(90, wait),
                    "legacy opening wait splits once: " + wait);
            check(MatchConfigModel.totalPhaseSeconds(migrated) == MatchConfigModel.totalPhaseSeconds(old)
                    && migrated.phases().get(1).equals(old.phases().get(1))
                    && migrated.phases().getFirst().damagePerSecond() == .2,
                    "migration preserves total time, later phases and damage");
            check(MatchConfigModel.parse(MatchConfigModel.toJson(migrated)).equals(migrated),
                    "saved independent delay never migrates twice");
        }
        Settings standard = MatchConfigModel.defaults(1);
        var customFlow = new MatchConfigModel.FlowRules(17, 35, 200, 12, 24, 80, 4, .75, 15, 45, 4);
        Settings custom = new Settings(1, "custom", 1, 2, 1, 30, 2400, 60, true, false, true, false,
                standard.phases(), standard.bombingRules(), customFlow);
        check(MatchConfigModel.parse(MatchConfigModel.toJson(custom)).equals(custom),
                "all eleven authored flow fields survive serialization");
        check(MatchConfigModel.forMap(custom, 2).flowRules().equals(customFlow),
                "map remapping keeps independent flow rules");
        MatchRulePresets library = new MatchRulePresets();
        library.put("custom", custom);
        check(library.load("custom", 2).flowRules().equals(customFlow),
                "rule library loading preserves every flow setting");
        String json = MatchConfigModel.toJson(custom);
        rejectsJson(json, root -> root.add("flowRules", com.google.gson.JsonNull.INSTANCE), "null flow object rejected");
        rejectsJson(json, root -> root.getAsJsonObject("flowRules").remove("flightSeconds"), "partial flow object rejected");
        rejectsJson(json, root -> root.getAsJsonObject("flowRules").addProperty("firstCircleDelaySeconds", "90"),
                "flow numeric strings rejected");
        rejectsJson(json, root -> root.getAsJsonObject("flowRules").addProperty("bombingDurationSeconds", 0),
                "zero bombing duration rejected");
        rejectsJson(json, root -> root.getAsJsonObject("flowRules").addProperty("lateCenterBias", 1.01),
                "center bias cannot exceed one");
        rejectsJson(json, root -> root.getAsJsonObject("flowRules").addProperty("exposureMaxMultiplier", .99),
                "exposure multiplier cannot reduce base damage");
    }

    private static void dropRuleCompatibility() {
        String defaults = MatchConfigModel.toJson(MatchConfigModel.defaults(1));
        for (boolean oldValue : new boolean[]{false, true}) {
            JsonObject old = JsonParser.parseString(defaults).getAsJsonObject();
            old.remove("allowBlockDrops");
            old.remove("allowMobDrops");
            old.addProperty("allowEntityDrops", oldValue);
            Settings migrated = MatchConfigModel.parse(old.toString());
            check(migrated.allowBlockDrops() == oldValue && migrated.allowMobDrops() == oldValue,
                    "legacy drop permission migrates to both natural drop rules: " + oldValue);
            JsonObject saved = JsonParser.parseString(MatchConfigModel.toJson(migrated)).getAsJsonObject();
            check(!saved.has("allowEntityDrops") && saved.has("allowBlockDrops") && saved.has("allowMobDrops"),
                    "saving migrated rules removes the former blanket drop switch");
            old.addProperty("allowBlockDrops", !oldValue);
            Settings partlyMigrated = MatchConfigModel.parse(old.toString());
            check(partlyMigrated.allowBlockDrops() == !oldValue && partlyMigrated.allowMobDrops() == oldValue,
                    "each explicit new rule overrides the legacy fallback independently");
            old.remove("allowBlockDrops");
            old.addProperty("allowMobDrops", !oldValue);
            partlyMigrated = MatchConfigModel.parse(old.toString());
            check(partlyMigrated.allowBlockDrops() == oldValue && partlyMigrated.allowMobDrops() == !oldValue,
                    "mob rule can migrate independently of block rule");
        }
        for (boolean blocks : new boolean[]{false, true}) for (boolean mobs : new boolean[]{false, true}) {
            JsonObject source = JsonParser.parseString(defaults).getAsJsonObject();
            source.addProperty("allowEntityDrops", true);
            source.addProperty("allowBlockDrops", blocks);
            source.addProperty("allowMobDrops", mobs);
            Settings authored = MatchConfigModel.parse(source.toString());
            check(authored.allowBlockDrops() == blocks && authored.allowMobDrops() == mobs,
                    "explicit natural drop rules take priority over the former flag");
            check(MatchConfigModel.parse(MatchConfigModel.toJson(authored)).equals(authored),
                    "independent block and mob permissions survive save and reload");
            Settings remapped = MatchConfigModel.forMap(authored, 2);
            check(remapped.allowBlockDrops() == blocks && remapped.allowMobDrops() == mobs,
                    "copying rules to another map preserves both drop permissions");
        }
        JsonObject oldest = JsonParser.parseString(defaults).getAsJsonObject();
        oldest.remove("allowBlockDrops");
        oldest.remove("allowMobDrops");
        Settings fallback = MatchConfigModel.parse(oldest.toString());
        check(!fallback.allowBlockDrops() && !fallback.allowMobDrops(),
                "rules without any drop fields keep natural drops disabled by default");
        rejectsJson(defaults, root -> root.addProperty("allowBlockDrops", "true"), "block drop value must be boolean");
        rejectsJson(defaults, root -> root.add("allowMobDrops", com.google.gson.JsonNull.INSTANCE), "mob drop value cannot be null");
    }

    private static void equipmentRuleCompatibility() {
        String defaults = MatchConfigModel.toJson(MatchConfigModel.defaults(1));
        JsonObject legacy = JsonParser.parseString(defaults).getAsJsonObject();
        legacy.remove("equipmentPerCategoryLimit");
        check(MatchConfigModel.parse(legacy.toString()).equipmentPerCategoryLimit() == 1,
                "existing rules acquire one item per equipment category");
        for (int limit : new int[]{0, 1, 2, 64}) {
            JsonObject root = JsonParser.parseString(defaults).getAsJsonObject();
            root.addProperty("equipmentPerCategoryLimit", limit);
            Settings authored = MatchConfigModel.parse(root.toString());
            check(authored.equipmentPerCategoryLimit() == limit
                    && MatchConfigModel.parse(MatchConfigModel.toJson(authored)).equals(authored),
                    "equipment category limit survives JSON round trip: " + limit);
            check(MatchConfigModel.forMap(authored, 2).equipmentPerCategoryLimit() == limit,
                    "copying a rule to another map preserves equipment limit");
            MatchRulePresets library = new MatchRulePresets();
            library.put("equipment", authored);
            check(library.load("equipment", 2).equipmentPerCategoryLimit() == limit,
                    "named preset preserves category limit");
        }
        rejectsJson(defaults, root -> root.addProperty("equipmentPerCategoryLimit", -1), "negative equipment limit rejected");
        rejectsJson(defaults, root -> root.addProperty("equipmentPerCategoryLimit", 65), "unbounded equipment limit rejected");
        rejectsJson(defaults, root -> root.addProperty("equipmentPerCategoryLimit", 1.5), "fractional equipment limit rejected");
        rejectsJson(defaults, root -> root.addProperty("equipmentPerCategoryLimit", "1"), "equipment numeric string rejected");
    }

    private static void codecStrictness() {
        String json = MatchConfigModel.toJson(MatchConfigModel.defaults(1));
        rejects(() -> MatchConfigModel.parse(json + "{}"), "trailing JSON cannot be ignored");
        rejects(() -> MatchConfigModel.parse(json.replace("\"name\":", "\"name\":\"duplicate\",\"name\":")),
                "duplicate root keys are rejected before object materialization");
        rejects(() -> MatchConfigModel.parse(json.replace("\"waitSeconds\":", "\"waitSeconds\":0,\"waitSeconds\":")),
                "duplicate phase keys are rejected");
        rejectsJson(json, root -> root.addProperty("unknown", true), "unknown root field");
        rejectsJson(json, root -> root.remove("name"), "missing root field");
        rejectsJson(json, root -> root.add("name", com.google.gson.JsonNull.INSTANCE), "null root field");
        rejectsJson(json, root -> root.addProperty("minPlayers", "2"), "numeric-looking string is not a number");
        rejectsJson(json, root -> root.addProperty("teamSize", 1.5), "fractional integer cannot truncate");
        rejectsJson(json, root -> root.addProperty("mapId", 1.5), "fractional map identifier cannot truncate");
        rejectsJson(json, root -> root.addProperty("mapId", "1"), "map identifier must be numeric");
        rejectsJson(json, root -> root.addProperty("blueEnabled", "true"), "boolean-looking string is not boolean");
        rejectsJson(json, root -> root.add("phases", new JsonObject()), "phase list must be an array");
        rejectsJson(json, root -> root.add("phases", new JsonArray()), "empty phase list is invalid");
        rejectsJson(json, root -> first(root).addProperty("extra", 0), "unknown phase field");
        rejectsJson(json, root -> first(root).remove("randomCenter"), "missing phase field");
        rejectsJson(json, root -> first(root).add("damagePerSecond", com.google.gson.JsonNull.INSTANCE), "null phase field");
        rejectsJson(json, root -> first(root).addProperty("shrinkSeconds", 1.25), "fractional duration cannot truncate");
        rejectsJson(json, root -> first(root).addProperty("randomCenter", 1), "numeric boolean rejected");
        rejects(() -> MatchConfigModel.parse(json.replace("\"damagePerSecond\":0.2", "\"damagePerSecond\":1e309")),
                "nonfinite decoded double rejected");
        rejects(() -> MatchConfigModel.parse(json.replace("\"radiusRatio\":0.65", "\"radiusRatio\":NaN")),
                "NaN is not a valid radius");
        rejects(() -> MatchConfigModel.parse("[]"), "root must be an object");
        rejects(() -> MatchConfigModel.parse("null"), "null root rejected");
        rejects(() -> MatchConfigModel.parse(null), "missing JSON rejected");
        rejects(() -> MatchConfigModel.parse(" ".repeat(MatchConfigModel.MAX_JSON_BYTES + 1)), "oversized UTF-8 input rejected");
    }

    private static void boundsAndTimeCaps() {
        Settings defaults = MatchConfigModel.shortTimingPreset(1);
        String json = MatchConfigModel.toJson(defaults);
        for (long id : new long[]{0, -1, Long.MAX_VALUE})
            rejectsJson(json, root -> root.addProperty("mapId", id), "invalid saved-map identifier " + id);
        for (String field : List.of("minPlayers", "maxPlayers", "teamSize")) {
            rejectsJson(json, root -> root.addProperty(field, 0), field + " cannot be zero");
            rejectsJson(json, root -> root.addProperty(field, 101), field + " cannot exceed 100 in network JSON");
            rejectsJson(json, root -> root.addProperty(field, 101), field + " rejects historical overflow");
        }
        rejectsJson(json, root -> { root.addProperty("minPlayers", 5); root.addProperty("maxPlayers", 4); },
                "minimum cannot exceed maximum");
        rejectsJson(json, root -> { root.addProperty("maxPlayers", 2); root.addProperty("teamSize", 3); },
                "team size cannot exceed player cap");
        for (String field : List.of("preparationSeconds", "disconnectGraceSeconds")) {
            rejectsJson(json, root -> root.addProperty(field, -1), field + " negative bound");
            rejectsJson(json, root -> root.addProperty(field, 3601), field + " upper bound");
        }
        rejectsJson(json, root -> root.addProperty("maxMatchSeconds", 0), "combat cap must be positive");
        rejectsJson(json, root -> root.addProperty("maxMatchSeconds", 86401), "combat cap is bounded to one day");
        rejectsJson(json, root -> root.addProperty("maxMatchSeconds", 869), "combat cap cannot precede end of authored phases");
        rejectsJson(json, root -> { root.addProperty("blueEnabled", false); root.addProperty("maxMatchSeconds", 869); },
                "disabled blue zone does not silently bypass schedule validation");
        for (String name : new String[]{"", " ", "x".repeat(81), "bad\nname"})
            rejectsJson(json, root -> root.addProperty("name", name), "invalid template name");
        for (Phase phase : List.of(new Phase(-1, 1, 1, 0, false), new Phase(0, 0, 1, 0, false),
                new Phase(86401, 1, 1, 0, false), new Phase(0, 86401, 1, 0, false),
                new Phase(0, 1, 0, 0, false), new Phase(0, 1, -0.1, 0, false),
                new Phase(0, 1, 1.0001, 0, false), new Phase(0, 1, Double.NaN, 0, false),
                new Phase(0, 1, Double.POSITIVE_INFINITY, 0, false), new Phase(0, 1, 1, -1, false),
                new Phase(0, 1, 1, 10001, false), new Phase(0, 1, 1, Double.NaN, false),
                new Phase(0, 1, 1, Double.NEGATIVE_INFINITY, false))) {
            check(!MatchConfigModel.validate(phases(defaults, List.of(phase))).isEmpty(), "invalid authored phase is rejected");
            rejects(() -> MatchConfigModel.toJson(phases(defaults, List.of(phase))), "invalid phase cannot serialize");
        }
        rejects(() -> MatchConfigModel.toJson(phases(defaults, List.of(new Phase(86400, 1, 1, 0, false)))),
                "wait plus move cannot overflow one-day phase cap");
        check(MatchConfigModel.validate(phases(defaults, java.util.Collections.nCopies(16, new Phase(0, 1, 1, 0, false)))).isEmpty(),
                "exactly sixteen phases allowed");
        rejects(() -> MatchConfigModel.toJson(phases(defaults, java.util.Collections.nCopies(17, new Phase(0, 1, 1, 0, false)))),
                "seventeenth phase cannot serialize");
        rejectsJson(json, root -> {
            JsonArray array = root.getAsJsonArray("phases");
            while (array.size() < 17) array.add(first(root).deepCopy());
        }, "seventeenth phase cannot parse");
        Settings boundary = new Settings(2, "合法边界", 1, 1, 1, 0, 1, 0, false, false, false, true,
                List.of(new Phase(0, 1, 1, 0, false)));
        check(MatchConfigModel.parse(MatchConfigModel.toJson(boundary)).equals(boundary),
                "legal zero wait, zero damage and exact combat cap survive round trip");
        Settings equalCap = new Settings(1, defaults.name(), 2, 20, 1, 30, 870, 60, true, true, true, false,
                defaults.phases(), defaults.bombingRules(), defaults.flowRules());
        check(MatchConfigModel.validate(equalCap).isEmpty(), "combat cap equal to schedule is allowed");
    }

    private static void legacyRosterMigration() {
        for (int maximum = 21; maximum <= 100; maximum++) {
            CompoundTag original = storedTemplate(legacyJson(1, 2, maximum, 1), 7);
            MatchConfigSavedData restored = MatchConfigSavedData.load(original, null);
            check(restored.loadError().isEmpty() && restored.settings(1).maxPlayers() == maximum,
                    "valid authored capacities through 100 are preserved without clamping");
            check(restored.revision() == 7 && restored.save(new CompoundTag(), null).equals(original),
                    "reading a valid existing capacity preserves original storage and revision");
        }
        check(MatchConfigModel.parse(legacyJson(1, 1, 100, 4)).maxPlayers() == 100,
                "network authoring supports one hundred adjustable seats");
        CompoundTag baseline = storedTemplate(legacyJson(1, 1, 100, 4), 9);
        for (String key : List.of("minPlayers", "maxPlayers", "teamSize")) {
            for (Number invalid : List.<Number>of(0, -1, 101, 20.5)) {
                JsonObject json = JsonParser.parseString(legacyJson(1, 1, 100, 4)).getAsJsonObject();
                json.addProperty(key, invalid);
                corrupt(baseline, t -> t.put("templates", templateList(json.toString())), "invalid stored capacity remains protected");
            }
        }
    }

    private static void migrationBackupGate() {
        CompoundTag original = storedTemplate(legacyJson(1, 1, 100, 4), 23);
        MatchConfigSavedData restored = MatchConfigSavedData.load(original, null);
        restored.completeRosterMigration(Path.of("nonexistent-no-migration-needed.dat"));
        check(restored.loadError().isEmpty() && restored.revision() == 23 && restored.save(new CompoundTag(), null).equals(original),
                "100-player compatible rules never require or rewrite source files during reads");
    }

    private static String legacyJson(long mapId, int minimum, int maximum, int teamSize) {
        JsonObject json = JsonParser.parseString(MatchConfigModel.toJson(MatchConfigModel.defaults(mapId))).getAsJsonObject();
        json.addProperty("minPlayers", minimum); json.addProperty("maxPlayers", maximum); json.addProperty("teamSize", teamSize);
        return json.toString();
    }

    private static CompoundTag storedTemplate(String json, long revision) {
        CompoundTag tag = new CompoundTag(); tag.putInt("version", 1); tag.putLong("revision", revision);
        tag.put("templates", templateList(json)); return tag;
    }

    private static ListTag templateList(String... json) {
        ListTag list = new ListTag(); for (String template : json) list.add(StringTag.valueOf(template)); return list;
    }

    private static void templateIsolationAndRevision() {
        MatchConfigSavedData data = new MatchConfigSavedData();
        check(data.revision() == 0 && data.templates().isEmpty(), "new SavedData has no implicit persisted template");
        check(data.settings(1).equals(MatchConfigModel.defaults(1)), "unsaved map template reads project defaults");
        check(data.templates().isEmpty() && data.revision() == 0, "reading defaults does not mutate storage");
        Settings first = MatchConfigModel.defaults(1), second = MatchConfigModel.rankedTimingPreset(2);
        check(data.saveSettings(0, first).equals(first) && data.revision() == 1, "first save commits once");
        CompoundTag baseline = data.save(new CompoundTag(), null);
        rejects(() -> data.saveSettings(0, second), "stale revision cannot save another map");
        check(data.revision() == 1 && data.save(new CompoundTag(), null).equals(baseline), "revision conflict leaves all data intact");
        Settings invalid = phases(second, List.of(new Phase(0, 0, 1, 0, false)));
        rejects(() -> data.saveSettings(1, invalid), "invalid settings cannot save");
        check(data.revision() == 1 && data.save(new CompoundTag(), null).equals(baseline), "failed validation leaves serialized data intact");
        data.saveSettings(1, second);
        check(data.revision() == 2 && data.settings(1).equals(first) && data.settings(2).equals(second), "different map templates remain isolated");
        rejects(() -> data.templates().clear(), "template enumeration is immutable");
        CompoundTag persisted = data.save(new CompoundTag(), null);
        MatchConfigSavedData restored = MatchConfigSavedData.load(persisted, null);
        check(restored.loadError().isEmpty() && restored.revision() == 2 && restored.templates().equals(data.templates()),
                "NBT template and revision round trip");
        check(restored.save(new CompoundTag(), null).equals(persisted), "NBT round trip preserves all fields");
        restored.saveSettings(2, MatchConfigModel.rankedTimingPreset(1));
        check(restored.settings(2).equals(second) && data.settings(1).equals(first), "reloaded object owns its map updates independently");
    }

    private static void protectedNbt() {
        MatchConfigSavedData blank = MatchConfigSavedData.load(new CompoundTag(), null);
        check(blank.loadError().isEmpty() && blank.templates().isEmpty(), "empty NBT starts with defaults");
        MatchConfigSavedData valid = new MatchConfigSavedData();
        valid.saveSettings(0, MatchConfigModel.defaults(1));
        CompoundTag nbt = valid.save(new CompoundTag(), null);
        corrupt(nbt, tag -> tag.putInt("version", 2), "unknown version");
        corrupt(nbt, tag -> tag.remove("revision"), "missing root field");
        corrupt(nbt, tag -> tag.putString("revision", "1"), "wrong revision type");
        corrupt(nbt, tag -> tag.putLong("revision", -1), "negative revision");
        corrupt(nbt, tag -> tag.putInt("unknown", 1), "unknown NBT field");
        corrupt(nbt, tag -> tag.putString("templates", "wrong"), "wrong template type");
        corrupt(nbt, tag -> {
            ListTag list = new ListTag();
            list.add(new CompoundTag());
            tag.put("templates", list);
        }, "non-string template entry");
        corrupt(nbt, tag -> {
            ListTag list = new ListTag();
            list.add(StringTag.valueOf("{}"));
            tag.put("templates", list);
        }, "malformed template JSON");
        corrupt(nbt, tag -> {
            ListTag list = new ListTag();
            String json = MatchConfigModel.toJson(MatchConfigModel.defaults(1));
            list.add(StringTag.valueOf(json)); list.add(StringTag.valueOf(json));
            tag.put("templates", list);
        }, "duplicate map template");
        corrupt(nbt, tag -> {
            ListTag list = new ListTag();
            for (int i = 1; i <= 257; i++) list.add(StringTag.valueOf(MatchConfigModel.toJson(MatchConfigModel.defaults(i))));
            tag.put("templates", list);
        }, "more than 256 map templates");
    }

    private static void lastPlayedRuleSelections() {
        var data = new MatchConfigSavedData();
        var available = List.of("First", "Second", "Next draft", "Renamed");
        check(data.preferredRuleName(1, available).isEmpty(), "a new map does not inherit another map's rule");
        Settings first = named(MatchConfigModel.defaults(1), "First");
        Settings second = named(MatchConfigModel.defaults(2), "Second");
        data.saveSettings(0, first); data.saveSettings(1, second);
        check(data.preferredRuleName(1, available).equals("First"), "legacy applied map template supplies its own initial selection");
        data.rememberPlayedRule(first); data.rememberPlayedRule(second);
        check(data.revision() == 2, "remembering actual play does not invalidate independent template drafts");
        data.saveSettings(2, named(first, "Next draft"));
        check(data.preferredRuleName(1, available).equals("First") && data.preferredRuleName(2, available).equals("Second"),
                "saved future drafts do not replace the last actually played rule or another map's choice");
        check(data.preferredRuleName(3, available).isEmpty(), "an untouched map still has no implicit selection");
        data.renameRememberedRule("First", "Renamed");
        check(data.preferredRuleName(1, available).equals("Renamed") && data.preferredRuleName(2, available).equals("Second"),
                "renaming a preset follows only its existing map references");
        data.forgetRememberedRule("Second");
        check(data.preferredRuleName(2, available).isEmpty(), "deleting and recreating a same-named preset does not resurrect an old selection");
        check(data.preferredRuleName(1, List.of("First")).isEmpty(), "a missing last-played preset falls back safely");
        CompoundTag stored = data.save(new CompoundTag(), null);
        var loaded = MatchConfigSavedData.load(stored, null);
        check(loaded.loadError().isEmpty() && loaded.preferredRuleName(1, available).equals("Renamed")
                && loaded.preferredRuleName(2, available).isEmpty(), "last-played selections and deleted-rule markers survive save and restart");
        check(loaded.save(new CompoundTag(), null).equals(stored), "last-played metadata round trip retains templates and revisions");
        loaded.removeMap(1);
        check(loaded.preferredRuleName(1, available).isEmpty(), "reused map IDs cannot inherit removed-map history");
        corrupt(stored, tag -> tag.putString("lastPlayedRules", "wrong"), "wrong last-played metadata type");
        corrupt(stored, tag -> {
            var rows = tag.getList("lastPlayedRules", net.minecraft.nbt.Tag.TAG_COMPOUND);
            rows.add(rows.getCompound(0).copy());
        }, "duplicate last-played map identity");
        corrupt(stored, tag -> tag.getList("lastPlayedRules", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).putLong("mapId", 0), "invalid last-played map identity");
    }

    private static Settings named(Settings source, String name) {
        JsonObject json = JsonParser.parseString(MatchConfigModel.toJson(source)).getAsJsonObject();
        json.addProperty("name", name); return MatchConfigModel.parse(json.toString());
    }

    private static void corrupt(CompoundTag baseline, Consumer<CompoundTag> mutation, String reason) {
        CompoundTag broken = baseline.copy(); mutation.accept(broken);
        CompoundTag expected = broken.copy();
        MatchConfigSavedData protectedData = MatchConfigSavedData.load(broken, null);
        check(!protectedData.loadError().isEmpty(), reason + " is reported");
        check(protectedData.save(new CompoundTag(), null).equals(expected), reason + " preserves original NBT");
        rejects(() -> protectedData.settings(1), reason + " cannot silently return default rules");
        rejects(() -> protectedData.saveSettings(0, MatchConfigModel.defaults(1)), reason + " cannot overwrite corruption");
        broken.putString("afterLoad", "outside mutation");
        check(protectedData.save(new CompoundTag(), null).equals(expected), reason + " protection owns a defensive copy");
    }

    private static Settings phases(Settings s, List<Phase> value) {
        return new Settings(s.mapId(), s.name(), s.minPlayers(), s.maxPlayers(), s.teamSize(), s.preparationSeconds(),
                s.maxMatchSeconds(), s.disconnectGraceSeconds(), s.blueEnabled(), s.autoEnd(), s.spectateEliminated(),
                s.friendlyFire(), value, s.bombingRules(), s.flowRules());
    }

    private static JsonObject first(JsonObject root) { return root.getAsJsonArray("phases").get(0).getAsJsonObject(); }

    private static void rejectsJson(String json, Consumer<JsonObject> mutation, String reason) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        mutation.accept(root);
        rejects(() -> MatchConfigModel.parse(root.toString()), reason);
    }

    private static void rejects(Runnable action, String reason) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + reason);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
}
