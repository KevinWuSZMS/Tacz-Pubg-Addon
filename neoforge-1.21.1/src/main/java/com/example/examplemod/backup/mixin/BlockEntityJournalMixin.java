package com.example.examplemod.backup.mixin;

import com.example.examplemod.backup.MapResetService;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityJournalMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"))
    private void taczPubg$recordInventory(CallbackInfo ci) {
        MapResetService.blockEntityChanged((BlockEntity) (Object) this);
    }
    @Inject(method = {"loadWithComponents", "loadCustomOnly"}, at = @At("HEAD"), cancellable = true)
    private void taczPubg$protectData(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (MapResetService.protectedLiveEntity((BlockEntity) (Object) this)) ci.cancel();
    }
}
