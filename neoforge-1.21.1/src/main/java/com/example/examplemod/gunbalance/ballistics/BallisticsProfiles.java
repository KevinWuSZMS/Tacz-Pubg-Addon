package com.example.examplemod.gunbalance.ballistics;

import com.example.examplemod.localization.Texts;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;

/** Reviewed real-ammunition profiles. Unknown and explicitly preserved weapons keep their gunpack data. */
public final class BallisticsProfiles {
    public static final String RESOURCE = "/assets/tacz_pubg/realistic_ballistics.json";
    private static final Catalog CATALOG = load();

    public record Profile(ResourceLocation gunId, ResourceLocation dataId,
            double muzzleVelocityMps, double gravityMps2, double effectiveDragPerMeter) {
        public Profile {
            if (gunId == null || dataId == null || !Double.isFinite(muzzleVelocityMps) || muzzleVelocityMps <= 0
                    || !Double.isFinite(gravityMps2) || gravityMps2 < 0
                    || !Double.isFinite(effectiveDragPerMeter) || effectiveDragPerMeter < 0) {
                throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ballistics_profiles.invalid_real_ammunition_ballistic_profile"));
            }
        }
    }

    private record Catalog(Map<ResourceLocation, Profile> byGun, Map<ResourceLocation, Profile> byData) {}

    private BallisticsProfiles() {}

    public static Profile forGun(ResourceLocation gunId) { return CATALOG.byGun().get(gunId); }
    public static int activeProfileCount() { return CATALOG.byGun().size(); }

    /** Initial mode returns the exact input; repeated transforms always set absolute reviewed values. */
    public static JsonObject applyProfile(boolean experimental, ResourceLocation dataId, JsonObject raw) {
        return experimental ? transform(dataId, raw) : raw;
    }

    public static JsonObject transform(ResourceLocation dataId, JsonObject raw) {
        Profile profile = CATALOG.byData().get(dataId);
        if (profile == null || !raw.has("bullet") || !raw.get("bullet").isJsonObject()) return raw;
        JsonObject result = raw.deepCopy();
        JsonObject bullet = result.getAsJsonObject("bullet");
        // TaCZ's cached AMMO_SPEED and its UI use m/s. Launch mixin cancels GLOBAL exactly once.
        bullet.addProperty("speed", profile.muzzleVelocityMps());
        bullet.addProperty("gravity", profile.gravityMps2() / 400.0);
        // Constant per-tick friction is replaced by the distance-calibrated flight integrator.
        bullet.addProperty("friction", 0.0);
        double originalLife = bullet.has("life") ? bullet.get("life").getAsDouble() : 10.0;
        bullet.addProperty("life", Double.isFinite(originalLife) ? Math.max(10.0, originalLife) : 10.0);
        // The same barrel/ammunition does not gain 100 m/s just by switching to semi-automatic fire.
        // Leave all other mode behavior, and attachment/script speed modifiers, unchanged.
        JsonElement modes = result.get("fire_mode_adjust");
        if (modes != null && modes.isJsonObject()) {
            for (JsonElement mode : modes.getAsJsonObject().asMap().values()) {
                if (mode.isJsonObject() && mode.getAsJsonObject().has("speed"))
                    mode.getAsJsonObject().addProperty("speed", 0.0);
            }
        }
        return result;
    }

    private static Catalog load() {
        Map<ResourceLocation, Profile> byGun = new HashMap<>();
        Map<ResourceLocation, Profile> byData = new HashMap<>();
        try (InputStream stream = BallisticsProfiles.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", RESOURCE));
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("guns")) {
                JsonObject entry = element.getAsJsonObject();
                if (!"apply".equals(entry.get("action").getAsString())) continue;
                Profile profile = new Profile(ResourceLocation.parse(entry.get("gunId").getAsString()),
                        ResourceLocation.parse(entry.get("dataId").getAsString()),
                        entry.get("proposedMuzzleVelocityMps").getAsDouble(),
                        entry.get("gravityMps2").getAsDouble(), entry.get("effectiveDragPerMeter").getAsDouble());
                if (byGun.putIfAbsent(profile.gunId(), profile) != null)
                    throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ballistics_profiles.duplicate_gun_ballistic_profile") + profile.gunId());
                Profile previous = byData.putIfAbsent(profile.dataId(), profile);
                if (previous != null && (previous.muzzleVelocityMps() != profile.muzzleVelocityMps()
                        || previous.gravityMps2() != profile.gravityMps2()
                        || previous.effectiveDragPerMeter() != profile.effectiveDragPerMeter())) {
                    throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ballistics_profiles.conflicting_shared_data_ballistic_profile") + profile.dataId());
                }
            }
        } catch (Exception error) {
            // Never leave a partly loaded catalog: an invalid research resource preserves all native physics.
            LogUtils.getLogger().error("Unable to load reviewed external ballistics; retaining original gunpack physics", error);
            return new Catalog(Map.of(), Map.of());
        }
        return new Catalog(Map.copyOf(byGun), Map.copyOf(byData));
    }
}
