package com.example.examplemod.gunbalance;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.example.examplemod.gunbalance.ammo.AmmoProfiles;
import com.example.examplemod.gunbalance.ballistics.BallisticsProfiles;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProfiles;
import com.example.examplemod.gunbalance.roles.RoleBalanceProfiles;
import com.example.examplemod.lootspawn.LootGunAmmoLinks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

/** Exercise the production composition path against all installed raw gun snapshots, without a world. */
public final class GunDataExperimentPipelineChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path workspace = Files.exists(Path.of("docs/gun-balance/gun-assets.json")) ? Path.of("") : Path.of("..");
        JsonObject assets = JsonParser.parseString(Files.readString(workspace.resolve("docs/gun-balance/gun-assets.json"))).getAsJsonObject();
        Map<String, JsonObject> guns = new HashMap<>();
        for (JsonElement element : assets.getAsJsonArray("guns")) {
            JsonObject row = element.getAsJsonObject(); guns.put(row.get("id").getAsString(), row);
        }
        JsonObject approved;
        try (var stream = AmmoProfiles.class.getResourceAsStream(AmmoProfiles.RESOURCE)) {
            if (stream == null) throw new AssertionError("Missing approved ammunition catalog");
            approved = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        require(guns.size() == 424 && approved.getAsJsonArray("guns").size() == 424, "Complete installed population");
        require(RoleBalanceProfiles.activeProfileCount() == 386, "Reviewed role runtime catalog loaded");
        require(BallisticsProfiles.activeProfileCount() == 396, "Reviewed exterior runtime catalog loaded");
        require(HeavyWeaponProfiles.activeProfileCount() == 13, "Approved heavy runtime catalog loaded");
        int combinations = 0, changedSupply = 0;
        for (JsonElement element : approved.getAsJsonArray("guns")) {
            JsonObject row = element.getAsJsonObject();
            ResourceLocation gunId = ResourceLocation.parse(row.get("gunId").getAsString());
            ResourceLocation dataId = ResourceLocation.parse(row.get("dataId").getAsString());
            JsonObject asset = guns.get(gunId.toString());
            require(asset != null && asset.getAsJsonObject("rawIndex").get("data").getAsString().equals(dataId.toString()), "Reviewed source index/data identity");
            JsonObject raw = asset.getAsJsonObject("rawData");
            String original = raw.toString();
            String originalAmmo = raw.get("ammo").getAsString();
            String proposedAmmo = row.get("targetAmmo").isJsonNull() ? originalAmmo : row.get("targetAmmo").getAsString();
            if (!originalAmmo.equals(proposedAmmo)) changedSupply++;
            require(row.get("originalAmmo").getAsString().equals(originalAmmo), "Ammo review matches actual raw snapshot");
            JsonObject unchanged = GunDataExperimentPipeline.apply(dataId, raw, false, false);
            require(unchanged == raw && unchanged.toString().equals(original), "Both initial options return exact source");
            for (boolean balance : new boolean[] {false, true}) for (boolean ammo : new boolean[] {false, true}) {
                combinations++;
                JsonObject result = GunDataExperimentPipeline.apply(dataId, raw, balance, ammo);
                require(raw.toString().equals(original), "Pipeline never edits fresh source in place");
                require(result.get("ammo").getAsString().equals(ammo ? proposedAmmo : originalAmmo), "Supply switch is independent of balance");
                JsonObject matchingBalance = GunDataExperimentPipeline.apply(dataId, raw, balance, false).deepCopy();
                JsonObject withoutSupply = result.deepCopy(); matchingBalance.remove("ammo"); withoutSupply.remove("ammo");
                require(matchingBalance.equals(withoutSupply), "Ammo changes no physics, damage, effects, spread or mechanic field");
                require(JsonParser.parseString(result.toString()).equals(result), "Native JSON network-cache round trip keeps composed data");
                var heavy = HeavyWeaponProfiles.forGun(gunId);
                require(result.has("tacz_pubg_heavy_profile") == (balance && heavy != null), "Heavy identity marker belongs to approved balance transformations");
                if (balance && heavy != null)
                    require(result.get("tacz_pubg_heavy_profile").getAsString().equals(gunId.toString()), "Heavy identity marker selects the matching runtime profile");

                var effectiveLoaded = new HashMap<ResourceLocation, JsonObject>(); effectiveLoaded.put(gunId, result);
                var companion = LootGunAmmoLinks.resolve(gunId,
                        id -> ResourceLocation.parse(effectiveLoaded.get(id).get("ammo").getAsString()),
                        id -> effectiveLoaded.get(id).has("ammo_amount") ? effectiveLoaded.get(id).get("ammo_amount").getAsInt() : 30);
                require(companion.ammoId().toString().equals(ammo ? proposedAmmo : originalAmmo), "Respawn follows actual composed index");
                int capacity = raw.has("ammo_amount") ? raw.get("ammo_amount").getAsInt() : 30;
                require(companion.baseMagazineCapacity() == capacity && companion.piles() == (capacity + 29) / 30
                        && companion.roundsPerPile() == 30, "Supply preserves base magazine and 30-round companion policy");

                if (!balance) {
                    JsonObject onlyAmmo = raw.deepCopy(); onlyAmmo.addProperty("ammo", ammo ? proposedAmmo : originalAmmo);
                    require(result.equals(onlyAmmo), "Ammo-only mode preserves every original gun property");
                } else {
                    var role = RoleBalanceProfiles.forGun(gunId);
                    if (role != null) {
                        // TaCZ divides each projectile by bullet_amount later; JSON stores whole-shot damage.
                        near(result.getAsJsonObject("bullet").get("damage").getAsDouble(), role.damage10(), "Role whole-shot damage survives ballistics and ammo");
                        near(result.getAsJsonObject("bullet").getAsJsonObject("extra_damage").get("head_shot_multiplier").getAsDouble(), role.head(), "Role head multiplier survives composition");
                        near(result.getAsJsonObject("inaccuracy").get("aim").getAsDouble(), role.aim(), "Role aim spread survives composition");
                        near(result.getAsJsonObject("inaccuracy").get("stand").getAsDouble(), role.hip(), "Role hip spread survives composition");
                        near(result.get("aim_time").getAsDouble(), role.ads(), "Role ADS survives composition");
                        JsonObject modes = result.getAsJsonObject("fire_mode_adjust");
                        for (String name : modes.keySet()) {
                            JsonObject mode = modes.getAsJsonObject(name);
                            for (String field : new String[] {"damage", "head_shot_multiplier", "aim_inaccuracy", "other_inaccuracy"})
                                near(mode.get(field).getAsDouble(), 0, "Another composition layer cannot restore a mode escape");
                        }
                    }
                    var exterior = BallisticsProfiles.forGun(gunId);
                    if (exterior != null) {
                        JsonObject bullet = result.getAsJsonObject("bullet");
                        near(bullet.get("speed").getAsDouble(), exterior.muzzleVelocityMps(), "Exterior speed survives ammo/role composition");
                        near(bullet.get("gravity").getAsDouble(), exterior.gravityMps2() / 400, "Exterior drop survives ammo/role composition");
                        near(bullet.get("friction").getAsDouble(), 0, "Native constant drag disabled for reviewed distance drag");
                    }
                    for (String field : new String[] {"script", "script_param", "ammo_amount", "extended_mag_ammo_amount",
                            "bolt", "reload", "exclusive_attachments", "builtin_attachments", "allow_attachment_types"}) {
                        require(java.util.Objects.equals(raw.get(field), result.get(field)), "Author mechanics retained: " + field);
                    }
                    JsonObject originalBullet = raw.getAsJsonObject("bullet");
                    JsonObject bullet = result.getAsJsonObject("bullet");
                    for (String field : originalBullet.keySet()) if (!Set.of("damage", "extra_damage", "speed", "gravity", "friction", "life").contains(field)
                            && !(heavy != null && Set.of("explosion", "pierce").contains(field)))
                        require(originalBullet.get(field).equals(bullet.get(field)), "Special bullet behavior retained: " + field);
                    ResearchBalanceFactors.Factor factor = ResearchBalanceFactors.BY_DATA_ID.get(dataId);
                    if (factor != null && raw.has("recoil")) {
                        JsonObject previousControl = GunBalanceTransform.apply(raw, factor.damage(), factor.control());
                        require(previousControl.get("recoil").equals(result.get("recoil")), "Prior recoil control survives role and supply overlays");
                    }
                }
                require(GunDataExperimentPipeline.apply(dataId, raw, false, false) == raw, "Restoring both initial modes reloads exact raw source");
                require(GunDataExperimentPipeline.apply(dataId, raw, balance, ammo).equals(result), "Same fresh reload is deterministic");
            }
        }
        JsonObject unknown = JsonParser.parseString("{\"ammo\":\"test:private_ammo\",\"bullet\":{\"speed\":123,\"damage\":3}}").getAsJsonObject();
        for (boolean balance : new boolean[] {false, true}) for (boolean ammo : new boolean[] {false, true})
            require(GunDataExperimentPipeline.apply(ResourceLocation.parse("test:unknown_gun_data"), unknown, balance, ammo) == unknown, "Unreviewed gun identity preserved in all modes");
        require(combinations == 1696 && changedSupply == 340, "424 guns times four independent switch combinations");
        System.out.println("Production gun-data experiment pipeline checks passed: " + checks
                + "; combinations=1696, role targets=386, exterior targets=396, changed supply=340");
    }

    private static void near(double actual, double expected, String why) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-8, why + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
}
