package com.example.examplemod.gunbalance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pure JSON checks against all reviewed guns. Does not initialize Minecraft or TaCZ. */
public final class GunBalanceChecks {
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        Path root = project.resolve("test-inputs");
        Path candidateFile = root.resolve("proposed-gun-data.json");
        JsonObject candidates = read(candidateFile);
        JsonObject assets = read(root.resolve("gun-assets.json"));
        JsonObject factors = read(project.resolve("src/main/resources/data/tacz_pubg/gun_balance/researched.json"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidateFile)));
        require(hash.equals(factors.get("sourceSha256").getAsString()), "Factor snapshot hash differs from reviewed data");
        Map<String, JsonObject> byGun = new HashMap<>();
        for (JsonElement gun : assets.getAsJsonArray("guns")) {
            JsonObject asset = gun.getAsJsonObject();
            byGun.put(asset.get("id").getAsString(), asset.getAsJsonObject("rawData"));
        }
        Map<String, JsonObject> factorByGun = new HashMap<>();
        Map<String, String> sharedData = new HashMap<>();
        for (JsonElement element : factors.getAsJsonArray("guns")) {
            JsonObject factor = element.getAsJsonObject();
            String id = factor.get("gunId").getAsString();
            require(factorByGun.put(id, factor) == null, "Duplicate gun factor " + id);
            String pair = factor.get("damageFactor") + ":" + factor.get("controlFactor");
            String previous = sharedData.putIfAbsent(factor.get("dataId").getAsString(), pair);
            require(previous == null || previous.equals(pair), "Conflicting shared data factors");
        }
        int checked = 0;
        for (JsonElement element : candidates.getAsJsonArray("guns")) {
            JsonObject candidate = element.getAsJsonObject();
            String id = candidate.get("gunId").getAsString();
            JsonObject source = byGun.get(id);
            require(source != null, "Missing source " + id);
            JsonObject before = source.deepCopy();
            JsonObject factor = factorByGun.get(id);
            require(factor != null, "Missing exported factor " + id);
            require(candidate.get("originalDataRef").equals(factor.get("dataId")), "Wrong data reference " + id);
            double damage = factor.get("damageFactor").getAsDouble();
            double control = factor.get("controlFactor").getAsDouble();
            require(candidate.get("damageFactor").getAsDouble() == damage, "Wrong damage factor " + id);
            require(candidate.get("controlFactor").getAsDouble() == control, "Wrong control factor " + id);
            JsonObject adjusted = GunBalanceTransform.applyProfile("researched", source, damage, control);
            require(source.equals(before), "Source was mutated " + id);
            require(adjusted.equals(candidate.get("proposedData")), "Candidate parameter mismatch " + id);
            require(GunBalanceTransform.applyProfile("researched", source, damage, control).equals(adjusted),
                    "Repeated reload accumulates factors " + id);
            require(GunBalanceTransform.applyProfile("default", source, damage, control).equals(before),
                    "Default profile did not restore source " + id);
            checked++;
        }
        require(checked == 423 && factorByGun.size() == checked, "Expected all 423 reviewed valid guns");
        require(!factorByGun.containsKey("cib:error"), "Zero-damage debug gun must not receive damage/control factors");
        require(PubgScopeRules.SCOPE_IDS.size() == 9, "Expected all nine catalog scopes including historical canted");
        require(PubgScopePolicy.RULES.size() == byGun.size(), "Every current gun needs a reviewed policy row");
        JsonObject inventory = read(root.resolve("compatibility-inventory.json"));
        Map<String, Set<String>> tags = new HashMap<>();
        inventory.getAsJsonObject("tag_union").asMap().forEach((id, values) -> {
            Set<String> members = new HashSet<>();
            for (JsonElement value : values.getAsJsonArray()) members.add(value.getAsString());
            tags.put(id, members);
        });
        int policyGuns = 0, closedSlots = 0, allowedPairs = 0, cantedPairs = 0;
        for (JsonElement element : inventory.getAsJsonArray("guns")) {
            JsonObject gun = element.getAsJsonObject();
            String gunId = gun.get("id").getAsString();
            if (!byGun.containsKey(gunId)) continue;
            JsonObject original = byGun.get(gunId);
            JsonObject snapshot = original.deepCopy();
            boolean originalSlot = PubgScopeRules.hasScopeSlot(original);
            PubgScopePolicy.Rule rule = PubgScopePolicy.RULES.get(gunId);
            require(rule != null && rule.originalScopeSlot() == originalSlot, "Policy original capability mismatch: " + gunId);
            Set<String> originalTags = new HashSet<>();
            for (JsonElement tag : gun.getAsJsonArray("allow_tag_entries")) originalTags.add(tag.getAsString());
            Set<String> beforeIds = PubgScopeRules.resolveAllowedIds(originalTags, tags);
            Set<String> selected = PubgScopePolicy.allowedFor(gunId, originalSlot);
            Set<String> actual = PubgScopeRules.applyPolicy(originalTags, tags, selected, originalSlot);
            Set<String> expected = new HashSet<>(beforeIds);
            expected.removeAll(PubgScopeRules.SCOPE_IDS);
            if (originalSlot) expected.addAll(rule.allowedScopes());
            else {
                closedSlots++;
                require(rule.allowedScopes().isEmpty() && selected.isEmpty(), "Originally closed scope slot was opened: " + gunId);
            }
            require(actual.equals(expected), "Scope restriction/other attachment rule mismatch: " + gunId);
            require(original.equals(snapshot), "Scope policy modified raw gun data: " + gunId);
            for (String scope : PubgScopeRules.SCOPE_IDS) {
                require(actual.contains(scope) == (originalSlot && rule.allowedScopes().contains(scope)),
                        "Wrong scope/gun decision: " + gunId + " / " + scope);
                if (actual.contains(scope)) {
                    allowedPairs++;
                    if (scope.equals("cbrg:canted_sight")) cantedPairs++;
                }
            }
            policyGuns++;
        }
        require(policyGuns == 424 && closedSlots == 71, "All 424 guns and 71 originally closed slots need coverage");
        require(allowedPairs > 0 && allowedPairs < 353 * 9, "PUBG policy must restrict universal mounting");
        require(cantedPairs > 0, "Explicitly retained historical canted rules must be represented");
        require(PubgScopePolicy.allowedFor("future:unknown", true) == null, "Unknown future guns retain initial rules");
        require(PubgScopePolicy.allowedFor("future:unknown", false).isEmpty(), "Unknown gun without a scope slot stays closed");
        Set<String> cyclic = PubgScopeRules.resolveAllowedIds(Set.of("#test:a"), Map.of(
                "test:a", Set.of("#test:b", "test:grip"), "test:b", Set.of("#test:a", "cbrg:15x_scope")));
        require(cyclic.equals(Set.of("test:grip", "cbrg:15x_scope")), "Mixed/cyclic tags must resolve safely");
        Set<String> limited = PubgScopeRules.applyPolicy(Set.of("#test:mix"),
                Map.of("test:mix", Set.of("cbrg:15x_scope", "test:grip", "test:other_scope")),
                Set.of("cbrg:red_dot"), true);
        require(limited.equals(Set.of("cbrg:red_dot", "test:grip", "test:other_scope")),
                "Forbidden PUBG optics removed while other pack attachments remain unchanged");
        Set<String> closed = PubgScopeRules.applyPolicy(Set.of("test:muzzle", "cbrg:15x_scope"),
                Map.of(), PubgScopeRules.SCOPE_IDS, false);
        require(closed.equals(Set.of("test:muzzle")), "Even an incorrect caller cannot unlock an originally closed slot");
        System.out.println("GunBalanceChecks: " + checked + " reviewed balance candidates still match original analysis.");
        System.out.println("PUBG scope policy: " + policyGuns + " guns, " + allowedPairs + " allowed pairs, "
                + closedSlots + " original no-scope guns remain closed, " + cantedPairs + " historical canted pairs.");
        System.out.println("Every one of the 3816 gun/scope decisions checked; other attachment rules and raw data preserved.");
    }

    private static JsonObject read(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
