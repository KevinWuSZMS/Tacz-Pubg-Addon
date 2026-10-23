package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.lootconfig.LootConfigStore;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponExplosionPolicy;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponGunData;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProfiles;
import com.example.examplemod.gunbalance.heavy.HeavyWeaponProjectile;
import com.example.examplemod.zones.heavy.HeavyWeaponCatalog;
import com.example.examplemod.zones.heavy.HeavyWeaponTerrainPolicy;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freezes a real player's native resource identity and per-gun terrain choice after launch evaluation. */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class EntityKineticBulletHeavyTerrainMixin implements HeavyWeaponProjectile {
    @Shadow private boolean explosionDestroyBlock;
    @Unique private HeavyWeaponProfiles.Profile taczPubg$heavyProfile;
    @Unique private boolean taczPubg$grenade;

    @Override public HeavyWeaponProfiles.Profile taczPubg$heavyWeaponProfile() { return taczPubg$heavyProfile; }
    @Override public boolean taczPubg$isGrenade() { return taczPubg$grenade; }

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;ZLcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V",
            at = @At("TAIL"), remap = false)
    private void taczPubg$freezePlayerHeavyTerrain(EntityType<?> type, Level level, LivingEntity shooter,
            ItemStack gun, ResourceLocation ammoId, ResourceLocation gunId, ResourceLocation displayId,
            boolean tracer, GunData gunData, BulletData bulletData, CallbackInfo callback) {
        taczPubg$grenade = HeavyWeaponCatalog.isGrenade(gunId);
        if (!(shooter instanceof ServerPlayer player) || !(gun.getItem() instanceof IGun source)
                || !gunId.equals(source.getGunId(gun)) || !HeavyWeaponCatalog.isHeavy(gunId)) return;
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof IGun active) || !gunId.equals(active.getGunId(held))) return;
        taczPubg$freezeApprovedHeavyProfile(gunId, gunData);
        var settings = LootConfigStore.get(player.serverLevel()).document().general().zoneSettings();
        taczPubg$heavyProfile = HeavyWeaponProfiles.withCurve(taczPubg$heavyProfile, settings.heavyWeaponDamageCurves().get(gunId.toString()));
        Boolean override = settings.heavyWeaponTerrain().get(gunId.toString());
        explosionDestroyBlock = HeavyWeaponTerrainPolicy.resolve(explosionDestroyBlock, override,
                AmmoConfig.EXPLOSIVE_AMMO_DESTROYS_BLOCK.get());
    }

    @Unique private void taczPubg$freezeApprovedHeavyProfile(ResourceLocation gunId, GunData gunData) {
        String loadedProfile = gunData instanceof HeavyWeaponGunData loaded ? loaded.taczPubg$heavyProfileId() : null;
        taczPubg$heavyProfile = HeavyWeaponExplosionPolicy.freeze(gunId, loadedProfile);
    }
}
