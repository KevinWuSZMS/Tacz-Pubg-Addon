package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityPolicy;
import com.example.examplemod.visibility.VisibilityEntities;
import com.example.examplemod.visibility.client.VisibilityClient;
import com.tacz.guns.entity.TargetMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TargetMinecart.class, remap = false)
public abstract class TargetMinecartVisibilityMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true, remap = false)
    private void pubg$distance(double squared, CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(VisibilityPolicy.inRange(squared,
                VisibilityEntities.distance((TargetMinecart) (Object) this, VisibilityClient.settings())));
    }
}
