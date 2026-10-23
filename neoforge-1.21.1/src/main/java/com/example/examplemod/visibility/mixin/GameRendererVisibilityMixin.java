package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.client.VisibilityClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Projection clipping is independent of terrain distance; does not increase generated/rendered chunks. */
@Mixin(GameRenderer.class)
public abstract class GameRendererVisibilityMixin {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void pubg$projection(CallbackInfoReturnable<Float> result) {
        if (Minecraft.getInstance().level == null) return;
        var settings = VisibilityClient.settings();
        result.setReturnValue(Math.max(result.getReturnValue(), (float) Math.max(settings.players(), settings.targets()) + 64));
    }
}
