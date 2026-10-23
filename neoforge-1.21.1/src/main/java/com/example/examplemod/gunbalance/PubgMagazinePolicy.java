package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reviewed per-gun feed policy. Unreviewed IDs fail closed for these new magazines. */
public final class PubgMagazinePolicy {
    public record Rule(boolean originalMagazineSlot, boolean eligible, String category,
                       Set<String> families, Set<String> allowedMagazines, List<String> exclusionReasons) {}
    public static final Map<String, Rule> RULES = load();

    private PubgMagazinePolicy() {}

    public static Set<String> allowedFor(String gunId, boolean originalMagazineSlot) {
        Rule rule = RULES.get(gunId);
        return originalMagazineSlot && rule != null && rule.originalMagazineSlot() && rule.eligible()
                ? rule.allowedMagazines() : Set.of();
    }

    private static Map<String, Rule> load() {
        String path = "/data/tacz_pubg/gun_balance/pubg_magazine_policy.json";
        try (var input = PubgMagazinePolicy.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", path));
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.unknown_magazine_policy_schema"));
            Map<String, Rule> result = new HashMap<>();
            for (JsonElement entry : root.getAsJsonArray("guns")) {
                JsonObject gun = entry.getAsJsonObject();
                String id = gun.get("gunId").getAsString();
                Set<String> families = new HashSet<>();
                for (JsonElement family : gun.getAsJsonArray("families"))
                    if (!families.add(family.getAsString())) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.duplicate_family") + id);
                Set<String> magazines = new HashSet<>();
                for (JsonElement magazine : gun.getAsJsonArray("allowedMagazines"))
                    if (!PubgMagazineRules.MAGAZINE_IDS.contains(magazine.getAsString()) || !magazines.add(magazine.getAsString()))
                        throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.invalid_magazine") + id);
                boolean slot = gun.get("originalMagazineSlot").getAsBoolean();
                boolean eligible = gun.get("eligible").getAsBoolean();
                if ((!slot || !eligible) && !magazines.isEmpty()) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.unauthorized_slot") + id);
                if (!magazines.equals(PubgMagazineRules.forFamilies(families))) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.incomplete_magazine_family") + id);
                var reasons = new java.util.ArrayList<String>();
                for (JsonElement reason : gun.getAsJsonArray("exclusionReasons")) reasons.add(reason.getAsString());
                if (result.putIfAbsent(id, new Rule(slot, eligible, gun.get("category").getAsString(),
                        Set.copyOf(families), Set.copyOf(magazines), List.copyOf(reasons))) != null)
                    throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.duplicate_magazine_gun_policy") + id);
            }
            return Map.copyOf(result);
        } catch (java.io.IOException error) { throw new IllegalStateException(Texts.tr("error.tacz_pubg.pubg_magazine_policy.unable_to_read_magazine_policy"), error); }
    }
}
