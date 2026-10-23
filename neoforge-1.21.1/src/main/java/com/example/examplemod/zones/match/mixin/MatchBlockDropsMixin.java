package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchDropService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Includes explosion and face-emitted block loot, which can bypass BlockDropsEvent. */
@Mixin(Block.class)
public abstract class MatchBlockDropsMixin {
    @Inject(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), cancellable = true)
    private static void taczPubg$blockItem(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (MatchDropService.blocksBlockDrops(level, pos.getX() + .5, pos.getZ() + .5)) ci.cancel();
    }

    @Inject(method = "popResourceFromFace", at = @At("HEAD"), cancellable = true)
    private static void taczPubg$blockFaceItem(Level level, BlockPos pos, Direction direction, ItemStack stack, CallbackInfo ci) {
        if (MatchDropService.blocksBlockDrops(level, pos.getX() + .5, pos.getZ() + .5)) ci.cancel();
    }

    @Inject(method = "popExperience", at = @At("HEAD"), cancellable = true)
    private void taczPubg$blockExperience(ServerLevel level, BlockPos pos, int amount, CallbackInfo ci) {
        if (MatchDropService.blocksBlockDrops(level, pos.getX() + .5, pos.getZ() + .5)) ci.cancel();
    }
}
