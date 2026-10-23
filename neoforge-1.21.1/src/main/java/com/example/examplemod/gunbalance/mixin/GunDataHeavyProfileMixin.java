package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.heavy.HeavyWeaponGunData;
import com.google.gson.annotations.SerializedName;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gson loads this identity together with TaCZ's native gun fields, including its network snapshot. */
@Mixin(value = GunData.class, remap = false)
public abstract class GunDataHeavyProfileMixin implements HeavyWeaponGunData {
    @Unique
    @SerializedName("tacz_pubg_heavy_profile")
    private String taczPubg$heavyProfileId;

    @Override public String taczPubg$heavyProfileId() { return taczPubg$heavyProfileId; }
}
