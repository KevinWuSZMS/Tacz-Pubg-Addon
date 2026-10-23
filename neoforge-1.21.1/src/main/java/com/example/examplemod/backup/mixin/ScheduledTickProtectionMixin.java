package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ScheduledTickProtectionMixin {
    @Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectScheduledBlock(BlockPos pos, Block block, CallbackInfo ci) {
        if (MapResetService.isLocked((ServerLevel) (Object) this, pos)) ci.cancel();
    }
    @Inject(method = "tickFluid", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectScheduledFluid(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (MapResetService.isLocked((ServerLevel) (Object) this, pos)) ci.cancel();
    }
}
