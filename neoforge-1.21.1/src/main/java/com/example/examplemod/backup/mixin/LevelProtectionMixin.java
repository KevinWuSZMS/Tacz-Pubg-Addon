package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelProtectionMixin {
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void taczPubg$preventDrops(BlockPos pos, boolean drops, Entity entity, int recursion, CallbackInfoReturnable<Boolean> ci) {
        if ((Object) this instanceof ServerLevel level && MapResetService.isLocked(level, pos)) ci.setReturnValue(false);
    }
}
