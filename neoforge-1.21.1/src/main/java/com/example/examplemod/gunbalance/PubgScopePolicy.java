package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The same reviewed per-gun policy powers the illustrated table and native attachment rules. */
public final class PubgScopePolicy {
    public record Rule(boolean originalScopeSlot, Set<String> allowedScopes) {}
    public static final Map<String, Rule> RULES = load();

    private PubgScopePolicy() {}

    /** Unknown future guns retain initial pack rules; a missing original slot always wins. */
    public static Set<String> allowedFor(String gunId, boolean originalScopeSlot) {
        if (!originalScopeSlot) return Set.of();
        Rule rule = RULES.get(gunId);
        return rule == null ? null : rule.allowedScopes();
    }

    private static Map<String, Rule> load() {
        String path = "/data/tacz_pubg/gun_balance/pubg_scope_policy.json";
        try (var input = PubgScopePolicy.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", path));
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_policy.unknown_pubg_scope_policy_schema"));
            Map<String, Rule> result = new HashMap<>();
            for (JsonElement value : root.getAsJsonArray("guns")) {
                JsonObject gun = value.getAsJsonObject();
                String id = gun.get("gunId").getAsString();
                boolean slot = gun.get("originalScopeSlot").getAsBoolean();
                Set<String> scopes = new HashSet<>();
                for (JsonElement scope : gun.getAsJsonArray("allowedScopes")) {
                    String scopeId = scope.getAsString();
                    if (!PubgScopeRules.SCOPE_IDS.contains(scopeId) || !scopes.add(scopeId))
                        throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_policy.invalid_scope_policy_for") + id);
                }
                if (!slot && !scopes.isEmpty()) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_policy.cannot_create_a_scope_slot_for") + id);
                if (result.putIfAbsent(id, new Rule(slot, Set.copyOf(scopes))) != null)
                    throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_policy.duplicate_pubg_gun_policy") + id);
            }
            return Map.copyOf(result);
        } catch (java.io.IOException error) { throw new IllegalStateException(Texts.tr("error.tacz_pubg.pubg_scope_policy.unable_to_read_pubg_scope_policy"), error); }
    }
}
