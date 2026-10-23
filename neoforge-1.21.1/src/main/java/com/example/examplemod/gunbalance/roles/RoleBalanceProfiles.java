package com.example.examplemod.gunbalance.roles;

import com.example.examplemod.localization.Texts;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;

/** Approved roles overlay fresh gun data; the initial setting never edits a gunpack. */
public final class RoleBalanceProfiles {
    public static final String RESOURCE = "/assets/tacz_pubg/approved_role_balance.json";
    private static final RoleBalanceCatalog CATALOG = load();

    private RoleBalanceProfiles() {}

    public static int activeProfileCount() {
        return (int) CATALOG.byGun().values().stream().filter(entry -> entry.target() != null).count();
    }

    public static RoleBalanceTransform.Target forGun(ResourceLocation gunId) {
        RoleBalanceCatalog.Entry entry = CATALOG.byGun().get(gunId.toString());
        return entry == null ? null : entry.target();
    }

    public static JsonObject applyProfile(boolean experimental, ResourceLocation dataId, JsonObject raw) {
        return experimental ? transform(dataId, raw) : raw;
    }

    public static JsonObject transform(ResourceLocation dataId, JsonObject raw) {
        RoleBalanceCatalog.Entry entry = CATALOG.byData().get(dataId.toString());
        return entry == null || entry.target() == null ? raw : RoleBalanceTransform.apply(raw, entry.target());
    }

    private static RoleBalanceCatalog load() {
        try (var stream = RoleBalanceProfiles.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException(Texts.tr("error.tacz_pubg.resources.missing_resource", RESOURCE));
            return RoleBalanceCatalog.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception error) {
            // Atomic failure: no partially populated role map can silently affect only some weapons.
            LogUtils.getLogger().error("Unable to load approved gun roles; retaining earlier gun experiment parameters", error);
            return new RoleBalanceCatalog(Map.of(), Map.of());
        }
    }
}
