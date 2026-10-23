package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchDropService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only block containers are filtered; entity inventories and player tosses remain separate. */
@Mixin(Containers.class)
public abstract class MatchBlockContainerDropsMixin {
    @Inject(method = "dropContents(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/Container;)V",
            at = @At("HEAD"), cancellable = true)
    private static void taczPubg$blockContainer(Level level, BlockPos pos, Container inventory, CallbackInfo ci) {
        if (MatchDropService.blocksBlockDrops(level, pos.getX() + .5, pos.getZ() + .5)) ci.cancel();
    }

    @Inject(method = "dropContents(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/NonNullList;)V",
            at = @At("HEAD"), cancellable = true)
    private static void taczPubg$blockStacks(Level level, BlockPos pos, NonNullList<ItemStack> stacks, CallbackInfo ci) {
        if (MatchDropService.blocksBlockDrops(level, pos.getX() + .5, pos.getZ() + .5)) ci.cancel();
    }
}
