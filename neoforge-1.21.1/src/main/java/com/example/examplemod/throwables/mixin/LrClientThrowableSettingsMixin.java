package com.example.examplemod.throwables.mixin;

import com.example.examplemod.throwables.ThrowableSettingsService;
import me.xjqsh.lrtactical.item.index.ThrowableIndex;
import me.xjqsh.lrtactical.resource.CommonNetworkCache;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

@Mixin(value = CommonNetworkCache.class, remap = false)
public abstract class LrClientThrowableSettingsMixin {
    @Inject(method = "parseThrowableIndex", at = @At("RETURN"))
    private void pubg$rememberDefinitions(Map<ResourceLocation, String> definitions, CallbackInfo ci) {
        ThrowableSettingsService.rememberNetworkSources(definitions,
                ((CommonNetworkCache) (Object) this).throwableIndex);
    }
    @Inject(method = "getThrowableIndex", at = @At("RETURN"), cancellable = true)
    private void pubg$settings(ResourceLocation id, CallbackInfoReturnable<ThrowableIndex<?, ?>> ci) {
        ci.setReturnValue(ThrowableSettingsService.overrideIndex(ci.getReturnValue()));
    }
}
