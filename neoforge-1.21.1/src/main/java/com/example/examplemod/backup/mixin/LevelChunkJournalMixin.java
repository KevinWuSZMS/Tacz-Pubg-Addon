package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkJournalMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void taczPubg$recordBeforeWrite(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> ci) {
        if (!MapResetService.beforeBlockChange((LevelChunk) (Object) this, pos, state)) ci.setReturnValue(null);
    }
    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectBlockEntity(BlockPos pos, CallbackInfo ci) {
        var chunk = (LevelChunk) (Object) this;
        if (!MapResetService.beforeBlockChange(chunk, pos, null)) ci.cancel();
    }
    @Inject(method = "setBlockEntity", at = @At("HEAD"), cancellable = true)
    private void taczPubg$recordEntityReplacement(BlockEntity entity, CallbackInfo ci) {
        var chunk = (LevelChunk) (Object) this;
        var previous = chunk.getBlockEntities().get(entity.getBlockPos());
        // Initial chunk deserialization is not a gameplay mutation.
        if (previous != null && previous != entity && !MapResetService.beforeBlockChange(chunk, entity.getBlockPos(), null)) ci.cancel();
    }
}
