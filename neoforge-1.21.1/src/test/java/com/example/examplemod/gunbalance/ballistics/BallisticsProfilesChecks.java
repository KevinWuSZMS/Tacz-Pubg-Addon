package com.example.examplemod.gunbalance.ballistics;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

public final class BallisticsProfilesChecks {
    public static void main(String[] args) throws Exception {
        int count = 0;
        try (var stream = BallisticsProfiles.class.getResourceAsStream(BallisticsProfiles.RESOURCE)) {
            if (stream == null) throw new AssertionError("Missing reviewed research resource");
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var element : root.getAsJsonArray("guns")) {
                var entry = element.getAsJsonObject();
                var gunId = ResourceLocation.parse(entry.get("gunId").getAsString());
                var dataId = ResourceLocation.parse(entry.get("dataId").getAsString());
                JsonObject source = JsonParser.parseString("{\"bullet\":{\"speed\":123,\"gravity\":0.01,\"friction\":0.03,\"life\":2},\"other\":7,\"fire_mode_adjust\":{\"semi\":{\"speed\":100,\"damage\":0.8},\"auto\":{\"speed\":-30,\"rpm\":0.5}}}").getAsJsonObject();
                String original = source.toString();
                if (BallisticsProfiles.applyProfile(false, dataId, source) != source || !source.toString().equals(original))
                    throw new AssertionError("Initial profile must be unchanged: " + dataId);
                JsonObject result = BallisticsProfiles.transform(dataId, source);
                if ("apply".equals(entry.get("action").getAsString())) {
                    if (BallisticsProfiles.forGun(gunId) == null) throw new AssertionError("Gun profile not loaded: " + gunId);
                    var bullet = result.getAsJsonObject("bullet");
                    near(bullet.get("speed").getAsDouble(), entry.get("proposedMuzzleVelocityMps").getAsDouble());
                    near(bullet.get("gravity").getAsDouble(), entry.get("gravityMps2").getAsDouble() / 400);
                    near(bullet.get("friction").getAsDouble(), 0);
                    near(bullet.get("life").getAsDouble(), 10);
                    var modes = result.getAsJsonObject("fire_mode_adjust");
                    near(modes.getAsJsonObject("semi").get("speed").getAsDouble(), 0);
                    near(modes.getAsJsonObject("auto").get("speed").getAsDouble(), 0);
                    near(modes.getAsJsonObject("semi").get("damage").getAsDouble(), .8);
                    near(modes.getAsJsonObject("auto").get("rpm").getAsDouble(), .5);
                    if (!BallisticsProfiles.transform(dataId, result).equals(result)) throw new AssertionError("Transform compounded twice");
                    if (!source.toString().equals(original)) throw new AssertionError("Source was mutated");
                } else if (result != source || BallisticsProfiles.forGun(gunId) != null) {
                    throw new AssertionError("Special/unknown weapon must preserve its original data: " + gunId);
                }
                count++;
            }
        }
        if (count < 400 || BallisticsProfiles.activeProfileCount() < 100) throw new AssertionError("Incomplete research catalog");
        var unknown = JsonParser.parseString("{\"bullet\":{\"speed\":999}}").getAsJsonObject();
        if (BallisticsProfiles.transform(ResourceLocation.parse("test:unknown"), unknown) != unknown)
            throw new AssertionError("Unreviewed data must be preserved");
        System.out.println("Ballistics profile entries checked: " + count + "; applied: " + BallisticsProfiles.activeProfileCount());
    }

    private static void near(double a, double b) {
        if (!Double.isFinite(a) || Math.abs(a - b) > 1e-10) throw new AssertionError(a + " != " + b);
    }
}
