package com.example.examplemod.gunbalance;

import com.example.examplemod.gunbalance.ammo.AmmoProfiles;
import com.example.examplemod.gunbalance.ballistics.BallisticsProfiles;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProfiles;
import com.example.examplemod.gunbalance.roles.RoleBalanceProfiles;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/** Independently compose approved rules from fresh source data, before TaCZ caches and syncs it. */
public final class GunDataExperimentPipeline {
    private GunDataExperimentPipeline() {}

    public static JsonObject apply(ResourceLocation dataId, JsonObject source, boolean balance, boolean ammo) {
        JsonObject result = source;
        if (balance) {
            ResearchBalanceFactors.Factor factor = ResearchBalanceFactors.BY_DATA_ID.get(dataId);
            if (factor != null) result = GunBalanceTransform.apply(result, factor.damage(), factor.control());
            // Role targets replace the old near-range damage/spread values; old recoil control remains.
            result = RoleBalanceProfiles.transform(dataId, result);
            result = BallisticsProfiles.transform(dataId, result);
            result = HeavyWeaponProfiles.transform(dataId, result);
        }
        // Supply identity changes do not imply damage, velocity, attachment, or loaded-round edits.
        return AmmoProfiles.applyProfile(ammo, dataId, result);
    }
}
