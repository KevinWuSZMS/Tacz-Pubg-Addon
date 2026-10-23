package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.client.VisibilityClient;
import com.tacz.guns.client.renderer.block.TargetRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TargetRenderer.class, remap = false)
public abstract class TargetRendererVisibilityMixin {
    @Inject(method = "getViewDistance", at = @At("HEAD"), cancellable = true, remap = false)
    private void pubg$distance(CallbackInfoReturnable<Integer> result) { result.setReturnValue(VisibilityClient.settings().targets()); }
}
