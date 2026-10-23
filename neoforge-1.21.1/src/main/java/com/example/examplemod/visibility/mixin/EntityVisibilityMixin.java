package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityEntities;
import com.example.examplemod.visibility.VisibilityPolicy;
import com.example.examplemod.visibility.client.VisibilityClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityVisibilityMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void pubg$renderDistance(double squared, CallbackInfoReturnable<Boolean> result) {
        Entity entity = (Entity) (Object) this;
        if (VisibilityEntities.managed(entity)) result.setReturnValue(VisibilityPolicy.inRange(squared,
                VisibilityEntities.distance(entity, VisibilityClient.settings())));
    }
    @Inject(method = "isAlwaysTicking", at = @At("HEAD"), cancellable = true)
    private void pubg$farMinecartInterpolation(CallbackInfoReturnable<Boolean> result) {
        Entity entity = (Entity) (Object) this;
        // Client only: packet lerp must continue without a native terrain chunk. Server simulation is unchanged.
        if (entity.level().isClientSide() && VisibilityEntities.managed(entity)) result.setReturnValue(true);
    }
}
