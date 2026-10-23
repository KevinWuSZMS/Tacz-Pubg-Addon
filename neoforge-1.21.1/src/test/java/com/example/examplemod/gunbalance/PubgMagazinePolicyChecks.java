package com.example.examplemod.gunbalance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Real inventory and reversible-policy checks; does not initialize Minecraft or launch a world. */
public final class PubgMagazinePolicyChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        require(PubgMagazineRules.MAGAZINE_IDS.size() == 9, "Exactly nine approved magazine IDs");
        require(PubgMagazinePolicy.RULES.size() == 424, "Reviewed 424-gun policy");
        require(PubgMagazinePolicy.allowedFor("future:unreviewed", true).isEmpty(), "Unknown gun fails closed");
        for (var entry : PubgMagazinePolicy.RULES.entrySet()) {
            var rule = entry.getValue();
            require(PubgMagazinePolicy.allowedFor(entry.getKey(), false).isEmpty(), "Never create a closed slot");
            require(rule.allowedMagazines().equals(PubgMagazineRules.forFamilies(rule.families())), "Complete family variants " + entry.getKey());
            if (!rule.eligible()) {
                require(rule.allowedMagazines().isEmpty() && !rule.exclusionReasons().isEmpty(), "Excluded gun has a reason and no grant");
                continue;
            }
            Set<String> expected = switch (rule.category()) {
                case "AR", "LMG" -> Set.of("rifle");
                case "SMG", "PISTOL" -> Set.of("smg");
                case "SR" -> Set.of("sniper");
                case "DMR" -> Set.of("rifle", "sniper");
                default -> throw new AssertionError("Unsupported eligible gun category");
            };
            require(expected.equals(rule.families()), "Correct native family " + entry.getKey());
        }
        for (String id : Set.of("cib:r8", "tacz:rhino357", "ccrp:marlin_1895", "tacz:kar98", "cib:type11", "tacz:lonetrail")) {
            require(PubgMagazinePolicy.RULES.get(id).originalMagazineSlot(), "Fixture exercises an originally open slot");
            require(PubgMagazinePolicy.allowedFor(id, true).isEmpty(), "Fixed feed exclusion " + id);
        }
        require(PubgMagazinePolicy.allowedFor("tacz:mk14", true).size() == 6, "DMR accepts both families in one slot");
        require(PubgMagazinePolicy.allowedFor("tacz:hk416d", true).size() == 3, "AR gets only its family");
        require(PubgMagazinePolicy.allowedFor("tacz:glock_17", true).size() == 3, "Pistol gets SMG family");

        String quick = "tacz_pubg_workbench:quickdraw_mag_rifle";
        String wrong = "tacz_pubg_workbench:quickdraw_mag_smg";
        Map<String, Set<String>> tags = Map.of(
                "test:nested", Set.of("#test:child", "old:scope", wrong),
                "test:child", Set.of("#test:nested", quick, "cbrg:quickdraw_mag_rifle", "old:stock"));
        Set<String> original = Set.of("#test:nested", "old:muzzle", "foreign:attachment");
        Set<String> originalLeaves = Set.of("old:scope", "old:stock", "old:muzzle", "foreign:attachment", "cbrg:quickdraw_mag_rifle");
        Set<String> ar = PubgMagazineRules.forFamilies(Set.of("rifle"));
        Set<String> experimental = PubgMagazineRules.applyPolicy(original, tags, ar, true);
        Set<String> oldAfter = new HashSet<>(experimental); oldAfter.removeAll(PubgMagazineRules.MAGAZINE_IDS);
        require(oldAfter.equals(originalLeaves), "All old nested tag leaves preserved");
        require(experimental.containsAll(ar) && !experimental.contains(wrong), "Wrong family indirect permission removed");
        Set<String> initial = PubgMagazineRules.applyPolicy(experimental, tags, ar, false);
        require(initial.equals(originalLeaves), "Initial removes all nine and nothing else");
        Set<String> again = PubgMagazineRules.applyPolicy(initial, tags, ar, true);
        require(again.equals(experimental), "Experimental -> initial -> experimental no accumulation");
        require(PubgMagazineRules.applyPolicy(experimental, tags, ar, true).equals(experimental), "Repeated enable is idempotent");

        JsonObject sample = JsonParser.parseString("{\"allow_attachment_types\":[\"extended_mag\",\"scope\"],\"ammo_amount\":20,\"extended_mag_ammo_amount\":[20,32,30,75],\"reload\":{\"type\":\"magazine\"}}").getAsJsonObject();
        String unchanged = sample.toString();
        require(PubgMagazineRules.supportsNativeMagazineLevels(sample), "Accept four-entry and non-monotonic native capacities");
        require(sample.toString().equals(unchanged), "Eligibility inspection never mutates gun data");
        for (String value : Set.of("[10,20]", "[10,0,30]", "[10,20,-1]", "[10,20,1.5]", "[10,20,\"30\"]")) {
            JsonObject bad = sample.deepCopy(); bad.add("extended_mag_ammo_amount", JsonParser.parseString(value));
            require(!PubgMagazineRules.supportsNativeMagazineLevels(bad), "Reject unsafe three-level table " + value);
        }
        JsonObject builtin = sample.deepCopy(); builtin.add("builtin_attachments", JsonParser.parseString("{\"extended_mag\":\"native:fixed\"}"));
        require(!PubgMagazineRules.supportsNativeMagazineLevels(builtin), "Do not replace a built-in magazine");
        JsonObject fuel = sample.deepCopy(); fuel.getAsJsonObject("reload").addProperty("type", "fuel");
        require(!PubgMagazineRules.supportsNativeMagazineLevels(fuel), "Fuel feed not a conventional magazine");

        Path root = Files.isRegularFile(Path.of("docs/compatibility/compatibility-inventory.json")) ? Path.of("") : Path.of("..");
        JsonObject inventory = JsonParser.parseString(Files.readString(root.resolve("docs/compatibility/compatibility-inventory.json"))).getAsJsonObject();
        int count = 0, eligible = 0;
        for (var value : inventory.getAsJsonArray("guns")) {
            JsonObject gun = value.getAsJsonObject();
            if (!gun.get("static_candidate").getAsBoolean()) continue;
            count++; String id = gun.get("id").getAsString();
            var rule = PubgMagazinePolicy.RULES.get(id); require(rule != null, "Every current gun reviewed " + id);
            if (rule.eligible()) eligible++;
            for (var rawCase : gun.getAsJsonArray("cases")) {
                JsonObject data = rawCase.getAsJsonObject().getAsJsonObject("raw_data");
                String before = data.toString();
                boolean supported = PubgMagazineRules.supportsNativeMagazineLevels(data);
                require(!rule.eligible() || supported, "Eligible gun has a real original slot/capacities " + id);
                require(data.toString().equals(before), "All original capacities and gun data unchanged " + id);
            }
        }
        require(count == 424 && eligible == 292, "Frozen inventory policy coverage");
        System.out.println("PubgMagazinePolicyChecks passed: 424 guns, 292 eligible, families/DMR/fixed feeds, nested-tag preservation, reversible modes, untouched native capacity tables.");
    }
}
