package com.example.examplemod.lootconfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import com.example.examplemod.lootconfig.LootConfigModel.AmmoOption;
import com.example.examplemod.lootconfig.LootConfigModel.Document;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;
import com.example.examplemod.lootconfig.LootConfigModel.GunSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Strict authoring/transfer/default checks; no worlds or gun-pack runtime are started. */
public final class GunAmmoConfigChecks {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }
    private static void rejects(Runnable action, String label) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | DecoderException expected) { return; }
        throw new AssertionError(label);
    }
    private static Document document(GunAmmoSupply supply) {
        return new Document(General.defaults(), List.of(new Entry("gun", "tacz:m4a1", 1, 1, 2.5, supply)),
                GunSettings.defaults(), LootConfigModel.defaultCategoryProbabilities());
    }
    private static JsonObject configured(GunAmmoSupply supply) {
        return JsonParser.parseString(LootConfigModel.toJson(document(supply))).getAsJsonObject();
    }
    private static JsonObject supply(JsonObject root) {
        return root.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("ammoSupply");
    }
    private static JsonObject option(JsonObject root) {
        return supply(root).getAsJsonArray("ammoOptions").get(0).getAsJsonObject();
    }
    private static void rejectsJson(Consumer<JsonObject> mutate, String label) {
        JsonObject json = configured(GunAmmoSupply.defaults(30)); mutate.accept(json);
        rejects(() -> LootConfigModel.parse(json.toString()), label);
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
    private static List<Double> probabilities(GunAmmoSupply supply) {
        return supply.ammoOptions().stream().map(AmmoOption::probability).toList();
    }
    private static void expectedDefault(String type, String gun, String ammo, int rounds, boolean rifleFamily) {
        var value = LootCatalog.defaultGunAmmoSupply(type, gun, ammo);
        check(value.roundsPerGroup() == rounds, "Rounds: " + gun + " " + type);
        check(probabilities(value).equals(rifleFamily ? List.of(.3, .6, .1) : List.of(.6, .3, .1)), "Scheme probabilities: " + gun + " " + type);
        check(value.ammoOptions().stream().map(AmmoOption::groups).toList().equals(List.of(1, 2, 3)), "Default groups: " + gun);
    }

    public static void main(String[] args) throws Exception {
        registeredThrowableIdentity();
        GunAmmoSupply defaults = GunAmmoSupply.defaults(30);
        check(defaults.ammoOptions().equals(List.of(new AmmoOption("self", 1, .6), new AmmoOption("self", 2, .3),
                new AmmoOption("self", 3, .1))), "Three full schemes; self and groups1/2/3");
        check(GunAmmoSupply.defaults(30, false).equals(defaults), "One-argument factory retains other-gun default");
        check(probabilities(GunAmmoSupply.defaults(30, true)).equals(List.of(.3, .6, .1)), "Rifle/SMG/MG probabilities");
        check(LootConfigModel.validateGunAmmoSupply(defaults).isEmpty(), "Defaults valid");
        check(!LootConfigModel.validateGunAmmoSupply(null).isEmpty(), "Absent explicit supply is not resolved supply");
        Entry legacy = new Entry("gun", "tacz:m4a1", 1, 1, 7);
        check(legacy.ammoSupply() == null, "Five-argument constructor retains dynamic default");
        check(LootConfigModel.parse(LootConfigModel.toJson(document(null))).items().getFirst().ammoSupply() == null, "Old JSON stays dynamic");
        JsonObject explicitNull = configured(defaults);
        explicitNull.getAsJsonArray("items").get(0).getAsJsonObject().add("ammoSupply", com.google.gson.JsonNull.INSTANCE);
        check(LootConfigModel.parse(explicitNull.toString()).items().getFirst().ammoSupply() == null, "Explicit null remains dynamic");
        var authoredSupply = new GunAmmoSupply(List.of(new AmmoOption("tacz:9mm", 1, .2), new AmmoOption("self", 64, .5),
                new AmmoOption("tacz:9mm", 3, .3)), 4096);
        Document authored = document(authoredSupply);
        check(LootConfigModel.parse(LootConfigModel.toJson(authored)).equals(authored), "Custom duplicate ammo and scheme groups round-trip");
        check(!LootConfigModel.toJson(authored).contains("groupProbabilities"), "No second group-probability draw in schema");
        check(authored.items().getFirst().withWeight(0).ammoSupply().equals(authoredSupply), "Weight transforms preserve schemes");
        var options = new ArrayList<>(defaults.ammoOptions());
        var immutable = new GunAmmoSupply(options, 15); options.clear();
        check(immutable.ammoOptions().size() == 3, "Supply captures immutable list");
        try { immutable.ammoOptions().clear(); throw new AssertionError("Immutable options"); }
        catch (UnsupportedOperationException expected) { checks++; }
        for (int count : new int[]{0, 4097})
            check(!LootConfigModel.validateGunAmmoSupply(GunAmmoSupply.defaults(count)).isEmpty(), "Round bounds " + count);
        for (int count : new int[]{1, 4096})
            check(LootConfigModel.validateGunAmmoSupply(GunAmmoSupply.defaults(count)).isEmpty(), "Round boundary " + count);
        for (int groups : new int[]{0, 65}) {
            var invalid = new GunAmmoSupply(List.of(new AmmoOption("self", groups, .6), defaults.ammoOptions().get(1), defaults.ammoOptions().get(2)), 30);
            check(!LootConfigModel.validateGunAmmoSupply(invalid).isEmpty(), "Scheme group bounds " + groups);
        }
        var unfinished = new GunAmmoSupply(List.of(new AmmoOption("self", 1, .5), defaults.ammoOptions().get(1), defaults.ammoOptions().get(2)), 30);
        check(unfinished.ammoOptions().getFirst().probability() == .5, "Draft probabilities never normalized");
        rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(document(unfinished))), "Final parse rejects unfinished probability total");
        for (double bad : new double[]{-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            var invalid = new GunAmmoSupply(List.of(new AmmoOption("self", 1, bad), defaults.ammoOptions().get(1), defaults.ammoOptions().get(2)), 30);
            check(!LootConfigModel.validateGunAmmoSupply(invalid).isEmpty(), "Reject invalid scheme probability " + bad);
        }
        var tolerated = new GunAmmoSupply(List.of(new AmmoOption("self", 1, .6 + 5e-10), defaults.ammoOptions().get(1), defaults.ammoOptions().get(2)), 30);
        check(LootConfigModel.validateGunAmmoSupply(tolerated).isEmpty(), "Tolerance accepts floating-point noise only");
        for (String kind : List.of("ammo", "attachment", "item")) {
            var nonGun = new Document(General.defaults(), List.of(new Entry(kind, "test:entry", 1, 1, 1, defaults)));
            rejects(() -> LootConfigModel.parse(LootConfigModel.toJson(nonGun)), "Only gun carries supply: " + kind);
        }
        rejectsJson(j -> supply(j).addProperty("extra", true), "Unknown supply field rejected");
        rejectsJson(j -> supply(j).add("groupProbabilities", JsonParser.parseString("[0.3,0.6,0.1]")), "Abandoned second-draw field rejected");
        rejectsJson(j -> option(j).addProperty("extra", 1), "Unknown scheme field rejected");
        rejectsJson(j -> supply(j).getAsJsonArray("ammoOptions").remove(0), "Exactly three schemes required");
        rejectsJson(j -> option(j).addProperty("probability", "0.6"), "String probability rejected");
        rejectsJson(j -> supply(j).addProperty("roundsPerGroup", 1.5), "Fractional rounds rejected");
        rejectsJson(j -> option(j).addProperty("groups", 1.5), "Fractional groups rejected");
        rejectsJson(j -> option(j).remove("groups"), "Missing scheme groups rejected");
        rejectsJson(j -> option(j).addProperty("ammoId", "missing_namespace"), "Incomplete ammo ID rejected");
        String json = LootConfigModel.toJson(authored);
        rejects(() -> LootConfigModel.parse(json.replace("\"roundsPerGroup\": 4096", "\"roundsPerGroup\": 4096, \"roundsPerGroup\": 1")), "Duplicate nested fields rejected");
        check(String.join("", LootConfigPayloads.split(json)).equals(json), "Schemes use bounded lossless document transport");

        ResourceLocation self = ResourceLocation.parse("tacz:556x45");
        Set<ResourceLocation> existing = Set.of(self, ResourceLocation.parse("tacz:9mm"));
        check(LootCatalog.ammoSupplyValidationError(authoredSupply, self, existing::contains) == null, "Different spawned ammo does not change gun chambering");
        check(LootCatalog.ammoSupplyValidationError(defaults, self, existing::contains) == null, "Self resolves current server ammo");
        check(LootCatalog.ammoSupplyValidationError(defaults, null, existing::contains) != null, "Missing native ammo rejected");
        var missingAtZero = new GunAmmoSupply(List.of(new AmmoOption("self", 1, 1), new AmmoOption("test:unloaded", 2, 0), new AmmoOption("self", 3, 0)), 30);
        check(LootCatalog.ammoSupplyValidationError(missingAtZero, self, existing::contains) != null, "Zero-probability unloaded asset still rejected");

        for (String type : List.of("rifle", "ar", "smg", "mg", "lmg")) expectedDefault(type, "test:gun", "tacz:556x45", 30, true);
        for (String type : List.of("sniper", "dmr", "pistol")) expectedDefault(type, "test:gun", "tacz:9mm", 15, false);
        expectedDefault("shotgun", "test:gun", "tacz:12g", 10, false);
        for (String gun : List.of("tacz:spr15hb", "tacz:sks_tactical", "hamster:sks", "ccrp:m14_hbar"))
            expectedDefault("rifle", gun, "tacz:762x51", 15, false);
        for (String ammo : List.of("tacz:rpg_rocket", "cib:80", "suffuse:120mm")) expectedDefault("rpg", "test:rocket", ammo, 1, false);
        for (String ammo : List.of("tacz:40mm", "cib:35mm", "suffuse:35x32mm")) expectedDefault("rpg", "test:launcher", ammo, 3, false);
        expectedDefault("rpg", "suffuse:qlu11", "test:changed_ammo", 3, false);

        RegistryFriendlyByteBuf encoded = buffer();
        try {
            LootConfigPayloads.writeAmmoSupply(encoded, authoredSupply);
            check(LootConfigPayloads.readAmmoSupply(encoded).equals(authoredSupply), "Fixed three-scheme metadata codec round-trip");
            check(encoded.readableBytes() == 0, "Codec consumes exactly its payload");
        } finally { encoded.release(); }
        RegistryFriendlyByteBuf corrupt = buffer();
        try {
            corrupt.writeUtf("self", 128); corrupt.writeVarInt(1); corrupt.writeDouble(Double.NaN);
            corrupt.writeUtf("self", 128); corrupt.writeVarInt(2); corrupt.writeDouble(.3);
            corrupt.writeUtf("self", 128); corrupt.writeVarInt(3); corrupt.writeDouble(.1);
            corrupt.writeVarInt(30);
            rejects(() -> LootConfigPayloads.readAmmoSupply(corrupt), "Catalog decoder rejects nonfinite metadata");
        } finally { corrupt.release(); }
        Path auditInput = args.length > 0 ? Path.of(args[0]) : Path.of("test-inputs/per-gun-ammo-defaults-audit-input.json");
        if (args.length > 0 || Files.isRegularFile(auditInput)) verifyLocalGunAudit(auditInput);
        System.out.println("Per-gun ammo configuration checks passed: " + checks);
    }

    private static void registeredThrowableIdentity() {
        check(!LootCatalog.requiresAssetIdentity(false, false, null), "Ordinary mod items remain registry-ID choices");
        check(LootCatalog.requiresAssetIdentity(true, false, null), "TaCZ guns/ammo/attachments still require their asset IDs");
        check(LootCatalog.requiresAssetIdentity(false, true, me.xjqsh.lrtactical.api.item.IThrowable.EMPTY),
                "LR's generic metadata-backed throwable shell stays out of ordinary loot");
        check(LootCatalog.requiresAssetIdentity(false, true, null), "A throwable with no default identity is not a usable ordinary item");
        check(!LootCatalog.requiresAssetIdentity(false, true, ResourceLocation.parse("another_mod:grenade")),
                "A fixed native throwable from another mod is accepted without a hard-coded PUBG namespace exception");
        for (var kind : com.example.examplemod.throwables.ThrowableKind.values()) {
            var fixedId = com.example.examplemod.throwables.LrThrowableBridge.id(kind);
            check(!LootCatalog.requiresAssetIdentity(false, true, fixedId),
                    "Native PUBG throwable remains a selectable/savable item after adopting LR's interface: " + kind.id);
        }
    }

    private static void verifyLocalGunAudit(Path input) throws Exception {
        JsonObject report = JsonParser.parseString(Files.readString(input, StandardCharsets.UTF_8)).getAsJsonObject();
        var guns = report.getAsJsonArray("guns");
        check(guns.size() == 424 && report.get("gunCount").getAsInt() == 424, "Offline audit covers all 424 validated local guns");
        Set<String> ids = new java.util.HashSet<>();
        int rifleFamily = 0;
        for (var element : guns) {
            JsonObject gun = element.getAsJsonObject();
            String id = gun.get("gunId").getAsString();
            check(ids.add(id), "Audit gun ID unique: " + id);
            var actual = LootCatalog.defaultGunAmmoSupply(gun.get("type").getAsString(), id, gun.get("nativeAmmoId").getAsString());
            List<AmmoOption> options = new ArrayList<>();
            for (var value : gun.getAsJsonArray("ammoOptions")) {
                JsonObject option = value.getAsJsonObject();
                options.add(new AmmoOption(option.get("ammoId").getAsString(), option.get("groups").getAsInt(), option.get("probability").getAsDouble()));
            }
            check(actual.equals(new GunAmmoSupply(options, gun.get("roundsPerGroup").getAsInt())), "Actual production classifier matches audited gun: " + id);
            if (actual.ammoOptions().getFirst().probability() == .3) rifleFamily++;
        }
        check(rifleFamily == 239, "239 AR/SMG/MG defaults use 30/60/10 percent");
        report.addProperty("status", "passed: production Java classifier matches all 424 offline native assets and approved roles");
        report.addProperty("productionJavaClassificationVerified", true);
        report.addProperty("javaValidationGunCount", guns.size());
        Files.writeString(input.resolveSibling("per-gun-ammo-defaults-audit.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report), StandardCharsets.UTF_8);
    }
}
