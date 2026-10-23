package com.example.examplemod.zones.client.visual.mixin;

import com.example.examplemod.zones.client.visual.VoxyBlueZoneDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional observer; the original Voxy pipeline always runs to completion unchanged. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public abstract class VoxyBlueZoneDepthMixin {
    @Inject(method = "runPipeline(Lme/cortex/voxy/client/core/rendering/Viewport;III)V",
            at = @At("TAIL"), require = 0, remap = false)
    private void pubg$captureBlueZoneDepth(@Coerce Object viewport, int sourceFramebuffer,
            int sourceWidth, int sourceHeight, CallbackInfo ci) {
        VoxyBlueZoneDepth.capture(this, viewport);
    }
}
