package com.example.examplemod.zones.heavy;

import com.example.examplemod.gunbalance.heavy.HeavyWeaponProfiles;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/** The catalog's actual wire codec preserves independent damage and terrain values. */
public final class HeavyWeaponMetadataChecks {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        var initial = new HeavyWeaponInfo("tacz:rpg_rocket", 20, 120, 3, true);
        check(initial.terrainPower() == 3 && !initial.experimentalBalance(), "Original metadata uses native radius as terrain strength");
        roundTrip(initial);
        for (String id : new String[]{"cib:dprkrpg", "cib:dzj08", "cib:qlu11", "classicr:mgl_40mm", "rainforest:at4",
                "rainforest:m72", "suffuse:aiyasinrpg", "suffuse:m203", "suffuse:m79", "suffuse:pf98a",
                "suffuse:qlu11", "tacz:m320", "tacz:rpg7"}) {
            var target = HeavyWeaponProfiles.forGun(net.minecraft.resources.ResourceLocation.parse(id));
            check(target != null, "Approved resource includes " + id);
            var info = new HeavyWeaponInfo("tacz:rpg_rocket", target.directDamage(), target.explosionDamage(),
                    target.damageRadius(), true, target.terrainPower(), true);
            roundTrip(info);
            check(info.damageRadius() >= 8 && info.terrainPower() <= 6.5, "Independent damage radius and terrain energy " + id);
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            rejects(new HeavyWeaponInfo("test:round", 1, 2, 3, false), invalid);
        }
        System.out.println("HeavyWeaponMetadataChecks: " + checks + " catalog wire, profile source and independent-strength checks passed.");
    }
    private static void roundTrip(HeavyWeaponInfo info) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            HeavyWeaponInfo.STREAM_CODEC.encode(buffer, info);
            check(HeavyWeaponInfo.STREAM_CODEC.decode(buffer).equals(info), "Catalog wire round-trip preserves all metadata");
            check(buffer.readableBytes() == 0, "Codec reads the complete metadata boundary");
        } finally { buffer.release(); }
    }
    private static void rejects(HeavyWeaponInfo info, double invalidTerrain) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf(info.ammoId(), 128); buffer.writeDouble(info.directDamage()); buffer.writeDouble(info.explosionDamage());
            buffer.writeDouble(info.damageRadius()); buffer.writeBoolean(info.nativeDestroyBlocks());
            buffer.writeDouble(invalidTerrain); buffer.writeBoolean(true);
            try { HeavyWeaponInfo.STREAM_CODEC.decode(buffer); throw new AssertionError("Invalid terrain metadata was accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        } finally { buffer.release(); }
    }
}
