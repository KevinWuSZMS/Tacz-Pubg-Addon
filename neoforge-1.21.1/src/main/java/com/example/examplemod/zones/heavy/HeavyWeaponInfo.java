package com.example.examplemod.zones.heavy;

import com.example.examplemod.localization.Texts;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Read-only values from the server's currently loaded native TaCZ gun metadata. */
public record HeavyWeaponInfo(String ammoId, double directDamage, double explosionDamage,
        double damageRadius, boolean nativeDestroyBlocks, double terrainPower, boolean experimentalBalance) {
    public static final StreamCodec<FriendlyByteBuf, HeavyWeaponInfo> STREAM_CODEC = StreamCodec.of((buffer, info) -> {
        buffer.writeUtf(info.ammoId(), 128);
        buffer.writeDouble(info.directDamage()); buffer.writeDouble(info.explosionDamage());
        buffer.writeDouble(info.damageRadius()); buffer.writeBoolean(info.nativeDestroyBlocks());
        buffer.writeDouble(info.terrainPower()); buffer.writeBoolean(info.experimentalBalance());
    }, buffer -> new HeavyWeaponInfo(buffer.readUtf(128), buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readBoolean(), buffer.readDouble(), buffer.readBoolean()));
    public HeavyWeaponInfo(String ammoId, double directDamage, double explosionDamage,
            double damageRadius, boolean nativeDestroyBlocks) {
        this(ammoId, directDamage, explosionDamage, damageRadius, nativeDestroyBlocks, damageRadius, false);
    }
    public HeavyWeaponInfo {
        if (ammoId == null || ammoId.length() > 128 || !ammoId.contains(":") || ResourceLocation.tryParse(ammoId) == null || !finiteNonnegative(directDamage)
                || !finiteNonnegative(explosionDamage) || !finiteNonnegative(damageRadius) || !finiteNonnegative(terrainPower))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.heavy_weapon_info.heavy_weapon_data_include_ammo_id_valid_damage"));
    }
    private static boolean finiteNonnegative(double value) { return Double.isFinite(value) && value >= 0; }
}
