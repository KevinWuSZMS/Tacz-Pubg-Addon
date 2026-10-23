package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Explicit scope catalog and pure, additive gun-slot conversion. */
public final class PubgScopeRules {
    public static final Set<String> SCOPE_IDS = loadScopeIds();

    private PubgScopeRules() {}

    public static Set<String> extendAllowedScopes(Collection<String> original, Collection<String> scopes) {
        Set<String> merged = new LinkedHashSet<>(original);
        merged.addAll(scopes);
        return Set.copyOf(merged);
    }

    /** Replace only the designated PUBG scope family; all other attachment rules stay intact. */
    public static Set<String> applyPolicy(Collection<String> original, Map<String, Set<String>> tags,
            Set<String> allowedPubgScopes, boolean originallyAllowedScope) {
        Set<String> leaves = new LinkedHashSet<>(resolveAllowedIds(original, tags));
        leaves.removeAll(SCOPE_IDS);
        if (originallyAllowedScope) for (String id : allowedPubgScopes)
            if (SCOPE_IDS.contains(id)) leaves.add(id);
        return Set.copyOf(leaves);
    }

    public static Set<String> resolveAllowedIds(Collection<String> values, Map<String, Set<String>> tags) {
        Set<String> result = new LinkedHashSet<>();
        expand(values, tags, new HashSet<>(), result);
        return Set.copyOf(result);
    }

    private static void expand(Collection<String> values, Map<String, Set<String>> tags,
            Set<String> visited, Set<String> result) {
        for (String value : values) {
            if (!value.startsWith("#")) result.add(value);
            else if (visited.add(value)) expand(tags.getOrDefault(value.substring(1), Set.of()), tags, visited, result);
        }
    }

    public static boolean hasScopeSlot(JsonObject data) {
        JsonElement types = data.get("allow_attachment_types");
        if (types == null || !types.isJsonArray()) return false;
        for (JsonElement type : types.getAsJsonArray())
            if (type.isJsonPrimitive() && "scope".equalsIgnoreCase(type.getAsString())) return true;
        return false;
    }

    private static Set<String> loadScopeIds() {
        String path = "/data/tacz_pubg/gun_balance/pubg_scopes.json";
        try (var input = PubgScopeRules.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", path));
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_rules.unknown_pubg_scope_catalog_version"));
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement value : root.getAsJsonArray("scopeIds")) {
                String id = value.getAsString();
                if (!id.matches("cbrg:[a-z0-9/._-]+") || !ids.add(id))
                    throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_rules.invalid_or_duplicate_pubg_scope_id") + id);
            }
            if (ids.isEmpty()) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.pubg_scope_rules.empty_pubg_scope_catalog"));
            return Set.copyOf(ids);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(Texts.tr("error.tacz_pubg.pubg_scope_rules.unable_to_read_pubg_scope_catalog"), error);
        }
    }
}
