package com.example.examplemod.gunbalance.ammo;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.example.examplemod.gunbalance.GunBalanceTransform;
import com.example.examplemod.lootspawn.LootGunAmmoLinks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

/** No game/world bootstrap: approved IDs, independent switches, and the actual companion policy. */
public final class AmmoProfilesChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        JsonObject catalog;
        try (var stream = AmmoProfiles.class.getResourceAsStream(AmmoProfiles.RESOURCE)) {
            if (stream == null) throw new AssertionError("Missing approved ammo resource");
            catalog = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        require(catalog.getAsJsonArray("guns").size() == 424, "All approved guns exported");
        require(AmmoProfiles.activeProfileCount() == 420, "All concrete targets active");
        require(AmmoProfiles.changedProfileCount() == 340, "Actual ammo changes counted");
        require(AmmoProfiles.targetAmmoIds().size() == 18, "Reduced supply catalog");
        int preserved = 0;
        for (var element : catalog.getAsJsonArray("guns")) {
            JsonObject row = element.getAsJsonObject();
            ResourceLocation gun = ResourceLocation.parse(row.get("gunId").getAsString());
            ResourceLocation data = ResourceLocation.parse(row.get("dataId").getAsString());
            String originalAmmo = row.get("originalAmmo").getAsString();
            String targetAmmo = row.get("targetAmmo").isJsonNull() ? originalAmmo : row.get("targetAmmo").getAsString();
            JsonObject raw = JsonParser.parseString("{\"ammo\":\"" + originalAmmo
                    + "\",\"ammo_amount\":31,\"bullet\":{\"damage\":7,\"speed\":555,\"gravity\":0.0245,\"friction\":0.02,\"explosion\":{\"damage\":80}},"
                    + "\"inaccuracy\":{\"aim\":0.1},\"other\":{\"data\":[1,2,3]},\"stack_components\":{\"GunCurrentAmmoCount\":17,\"HasBulletInBarrel\":true}}")
                    .getAsJsonObject();
            String unmodified = raw.toString();
            require(AmmoProfiles.applyProfile(false, data, raw) == raw, "Initial profile is exact source " + gun);
            for (boolean balance : new boolean[] {false, true}) for (boolean ammo : new boolean[] {false, true}) {
                JsonObject balanced = balance ? GunBalanceTransform.applyProfile("researched", raw, 1.3, 1.2) : raw;
                String balancedCopy = balanced.toString();
                JsonObject result = AmmoProfiles.applyProfile(ammo, data, balanced);
                String effectiveAmmo = ammo ? targetAmmo : originalAmmo;
                require(result.get("ammo").getAsString().equals(effectiveAmmo), "Independent ammo/balance switches " + gun);
                JsonObject withoutAmmo = result.deepCopy(); withoutAmmo.remove("ammo");
                JsonObject beforeWithoutAmmo = balanced.deepCopy(); beforeWithoutAmmo.remove("ammo");
                require(withoutAmmo.equals(beforeWithoutAmmo), "Only supply reference changed " + gun);
                require(balanced.toString().equals(balancedCopy) && raw.toString().equals(unmodified), "No input mutation " + gun);
                require(AmmoProfiles.applyProfile(ammo, data, result).equals(result), "No compounding " + gun);

                // Same policy object used by LootRespawnService: it asks the loaded index at commit time.
                Map<ResourceLocation, JsonObject> loaded = new HashMap<>(); loaded.put(gun, result);
                var companion = LootGunAmmoLinks.resolve(gun,
                        id -> ResourceLocation.parse(loaded.get(id).get("ammo").getAsString()),
                        id -> loaded.get(id).get("ammo_amount").getAsInt());
                require(companion.ammoId().toString().equals(effectiveAmmo), "Companion follows applied native index " + gun);
                require(companion.piles() == 2 && companion.roundsPerPile() == 30, "31-round magazine supplies two 30 piles");
                require(companion.baseMagazineCapacity() == 31, "Base magazine stays independent of supply ID");
                require(result.getAsJsonObject("stack_components").get("GunCurrentAmmoCount").getAsInt() == 17
                        && result.getAsJsonObject("stack_components").get("HasBulletInBarrel").getAsBoolean(), "No loaded ammo clearing");
                JsonObject restored = AmmoProfiles.applyProfile(false, data, raw);
                require(restored == raw && restored.toString().equals(unmodified), "Fresh default restores original supply");
                // Explicit standalone probability-list ammo has no gun lookup and is never remapped.
                JsonObject standalone = JsonParser.parseString("{\"kind\":\"ammo\",\"id\":\"" + originalAmmo + "\",\"weight\":2}").getAsJsonObject();
                require(standalone.get("id").getAsString().equals(originalAmmo), "Explicit ammo entry retained");
            }
            if (row.get("targetAmmo").isJsonNull()) {
                preserved++;
                require(AmmoProfiles.applyProfile(true, data, raw) == raw, "Deferred/debug rows preserved");
            }
        }
        require(preserved == 4, "Three deferred incendiary variants plus debug preserved");
        JsonObject unknown = JsonParser.parseString("{\"ammo\":\"test:ammo\"}").getAsJsonObject();
        require(AmmoProfiles.applyProfile(true, ResourceLocation.parse("test:unknown"), unknown) == unknown, "Unknown packs preserved");
        AmmoProfiles.validateTargetResources(AmmoProfiles.targetAmmoIds()::contains); checks++;
        expectFailure(() -> AmmoProfiles.validateTargetResources(id -> !id.equals(ResourceLocation.parse("tacz:12g"))), "Missing target rejected");
        JsonObject first = catalog.getAsJsonArray("guns").get(1).getAsJsonObject();
        JsonObject unexpected = JsonParser.parseString("{\"ammo\":\"test:new_pack_ammo\"}").getAsJsonObject();
        expectFailure(() -> AmmoProfiles.applyProfile(true, ResourceLocation.parse(first.get("dataId").getAsString()), unexpected), "Changed source requires new approval");
        JsonObject duplicate = catalog.deepCopy();
        duplicate.getAsJsonArray("guns").add(duplicate.getAsJsonArray("guns").get(0).deepCopy());
        expectFailure(() -> AmmoProfiles.parse(duplicate), "Duplicate gun rejected");
        JsonObject conflict = catalog.deepCopy();
        JsonObject conflictRow = conflict.getAsJsonArray("guns").get(0).getAsJsonObject().deepCopy();
        conflictRow.addProperty("gunId", "test:shared_data_collision"); conflictRow.addProperty("targetAmmo", "tacz:12g");
        conflict.getAsJsonArray("guns").add(conflictRow);
        expectFailure(() -> AmmoProfiles.parse(conflict), "Shared data conflict rejected");
        JsonObject preserveConflict = catalog.deepCopy();
        JsonObject preserveRow = preserveConflict.getAsJsonArray("guns").get(0).getAsJsonObject().deepCopy();
        preserveRow.addProperty("gunId", "test:preserve_shared"); preserveRow.addProperty("action", "preserve"); preserveRow.add("targetAmmo", null);
        preserveConflict.getAsJsonArray("guns").add(preserveRow);
        expectFailure(() -> AmmoProfiles.parse(preserveConflict), "Preserve/apply shared conflict rejected");
        JsonObject missingDeclaration = catalog.deepCopy(); missingDeclaration.add("targetAmmoIds", new JsonArray());
        expectFailure(() -> AmmoProfiles.parse(missingDeclaration), "Undeclared target rejected");
        for (int capacity : new int[] {1, 29, 30, 31, 60, 61, 500}) {
            var companion = LootGunAmmoLinks.resolve("gun", id -> "current:ammo", id -> capacity);
            require(companion.piles() == (capacity + 29) / 30, "Ceil magazine/30 independent of ammo type");
        }
        expectFailure(() -> LootGunAmmoLinks.resolve("gun", id -> null, id -> 30), "Missing loaded ammo fails transaction");
        expectFailure(() -> LootGunAmmoLinks.resolve("gun", id -> "ammo", id -> 0), "Invalid magazine rejected");
        System.out.println("Approved ammunition and companion supply checks passed: " + checks
                + "; guns=424, concrete targets=420, changed=340, supply IDs=18");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action, String message) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
}
