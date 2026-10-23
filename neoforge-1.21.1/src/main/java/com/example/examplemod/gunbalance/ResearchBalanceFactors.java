package com.example.examplemod.gunbalance;

import com.example.examplemod.localization.Texts;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;

/** Versioned factors generated from docs/gun-balance/proposed-gun-data.json. */
final class ResearchBalanceFactors {
    static final Map<ResourceLocation, Factor> BY_DATA_ID = load();

    private ResearchBalanceFactors() {}

    private static Map<ResourceLocation, Factor> load() {
        String resource = "/data/tacz_pubg/gun_balance/researched.json";
        try (var input = ResearchBalanceFactors.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException(Texts.tr("error.tacz_pubg.resources.missing_resource", resource));
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) {
                throw new IOException(Texts.tr("error.tacz_pubg.research_balance_factors.unsupported_research_factor_schema"));
            }
            Map<ResourceLocation, Factor> result = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("guns")) {
                JsonObject gun = element.getAsJsonObject();
                ResourceLocation dataId = ResourceLocation.parse(gun.get("dataId").getAsString());
                Factor factor = new Factor(gun.get("gunId").getAsString(),
                        gun.get("damageFactor").getAsDouble(), gun.get("controlFactor").getAsDouble());
                if (!Double.isFinite(factor.damage()) || factor.damage() <= 0
                        || !Double.isFinite(factor.control()) || factor.control() <= 0) {
                    throw new IOException(Texts.tr("error.tacz_pubg.research_balance_factors.invalid_factors_for") + dataId);
                }
                Factor previous = result.putIfAbsent(dataId, factor);
                if (previous != null && (previous.damage() != factor.damage()
                        || previous.control() != factor.control())) {
                    throw new IOException(Texts.tr("error.tacz_pubg.research_balance_factors.conflicting_factors_for_shared_gun_data") + dataId);
                }
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException(Texts.tr("error.tacz_pubg.research_balance_factors.unable_to_read_tacz_pubg_researched_gun_balance"), error);
        }
    }

    record Factor(String gunId, double damage, double control) {}
}
