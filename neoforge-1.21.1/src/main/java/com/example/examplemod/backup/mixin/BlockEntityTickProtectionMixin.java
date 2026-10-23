package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickProtectionMixin {
    @Shadow @Final private BlockEntity blockEntity;
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void taczPubg$freezeProtectedMachines(CallbackInfo ci) {
        if (blockEntity.getLevel() instanceof ServerLevel level && MapResetService.isLocked(level, blockEntity.getBlockPos())) ci.cancel();
    }
}
