package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityEntities;
import com.example.examplemod.visibility.VisibilityPolicy;
import com.example.examplemod.visibility.client.VisibilityClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Carrier classes may override Entity's own distance test; keep the renderer's remaining frustum/depth path. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererVisibilityMixin {
    @WrapOperation(method = "shouldRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;shouldRender(DDD)Z"))
    private boolean pubg$managedDistance(Entity entity, double x, double y, double z, Operation<Boolean> original) {
        if (VisibilityEntities.managed(entity)) return VisibilityPolicy.inRange(entity.distanceToSqr(x, y, z),
                VisibilityEntities.distance(entity, VisibilityClient.settings()));
        return original.call(entity, x, y, z);
    }
}
