package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityPolicy;
import com.example.examplemod.visibility.VisibilityEntities;
import com.example.examplemod.visibility.client.VisibilityClient;
import net.minecraft.client.player.RemotePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RemotePlayer overrides Entity's distance test (including a native tenfold factor). */
@Mixin(RemotePlayer.class)
public abstract class RemotePlayerVisibilityMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void pubg$distance(double squared, CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(VisibilityPolicy.inRange(squared,
                VisibilityEntities.distance((RemotePlayer) (Object) this, VisibilityClient.settings())));
    }
}
