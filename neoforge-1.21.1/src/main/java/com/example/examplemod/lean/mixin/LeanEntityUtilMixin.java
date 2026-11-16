package com.example.examplemod.lean.mixin;

import com.example.examplemod.lean.LeanHitboxes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityUtil.class, remap = false)
abstract class LeanEntityUtilMixin {
    @WrapOperation(method = {"findEntityOnPath", "findEntitiesOnPath"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"), require = 2)
    private static List<Entity> taczPubg$includeExposedHistory(Level level, Entity excluded, AABB bounds,
            Predicate<? super Entity> filter, Operation<List<Entity>> original) {
        return LeanHitboxes.candidates(level, original.call(level, excluded, bounds, filter), excluded, filter, bounds);
    }
    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true)
    private static void taczPubg$leanedBodyHit(Projectile bullet, Entity entity, Vec3 start, Vec3 end,
            CallbackInfoReturnable<EntityKineticBullet.EntityResult> callback) {
        if (entity instanceof Player player) {
            var trace = LeanHitboxes.trace(player, bullet, start, end);
            if (trace.customGeometry()) callback.setReturnValue(trace.hit());
        }
    }
}
