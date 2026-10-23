package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityEntities;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Managed objects use their own distance and frustum tests; terrain-section availability is independent. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumVisibilityMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true, remap = false)
    private void pubg$farManagedEntity(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (VisibilityEntities.managed(entity)) result.setReturnValue(true);
    }
}
