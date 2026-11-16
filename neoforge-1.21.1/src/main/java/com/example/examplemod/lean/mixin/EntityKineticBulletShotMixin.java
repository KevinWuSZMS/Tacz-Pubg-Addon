package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.LeanShots;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class EntityKineticBulletShotMixin {
    @Shadow private Vec3 startPos;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;ZLcom/tacz/guns/resource/pojo/data/gun/GunData;Lcom/tacz/guns/resource/pojo/data/gun/BulletData;)V",
            at = @At("TAIL"), remap = false)
    private void taczPubg$launchAtFrozenEye(EntityType<?> type, Level level, LivingEntity shooter, ItemStack gun,
            ResourceLocation ammoId, ResourceLocation gunId, ResourceLocation displayId, boolean tracer,
            GunData gunData, BulletData bulletData, CallbackInfo callback) {
        LeanShots.ShotPose pose = LeanShots.current(shooter);
        if (level.isClientSide || pose == null) return;
        ((EntityKineticBullet) (Object) this).setPos(pose.origin());
        startPos = pose.origin();
    }
}
