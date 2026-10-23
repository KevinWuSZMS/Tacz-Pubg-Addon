package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityEntities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The vanilla render loop separately requires compiled terrain even after entity frustum/distance checks. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVisibilityMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean pubg$farEntityDoesNotRequireTerrain(LevelRenderer renderer, BlockPos pos,
            Operation<Boolean> original, @Local Entity entity) {
        // A cached/sent chunk can still have no compiled render section at a lower local terrain distance.
        if (VisibilityEntities.managed(entity)) return true;
        return original.call(renderer, pos);
    }
}
