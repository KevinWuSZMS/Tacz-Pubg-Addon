package com.example.examplemod.throwables.mixin;

import com.example.examplemod.throwables.ThrowableSettingsService;
import me.xjqsh.lrtactical.item.index.ThrowableIndex;
import me.xjqsh.lrtactical.resource.CommonAssetsManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommonAssetsManager.class, remap = false)
public abstract class LrServerThrowableSettingsMixin {
    @Inject(method = "getThrowableIndex", at = @At("RETURN"), cancellable = true)
    private void pubg$settings(ResourceLocation id, CallbackInfoReturnable<ThrowableIndex<?, ?>> ci) {
        ci.setReturnValue(ThrowableSettingsService.overrideIndex(ci.getReturnValue()));
    }
}
