package com.example.examplemod.combat.mixin;

import com.example.examplemod.combat.CombatService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class CombatGameModeMixin {
    @Inject(method="useItem",at=@At("HEAD"),cancellable=true)
    private void taczPubg$noUse(ServerPlayer p,Level level,ItemStack stack,InteractionHand hand,CallbackInfoReturnable<InteractionResult> ci) {if(CombatService.actionBlocked(p))ci.setReturnValue(InteractionResult.FAIL);}
    @Inject(method="useItemOn",at=@At("HEAD"),cancellable=true)
    private void taczPubg$noUseBlock(ServerPlayer p,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> ci) {if(CombatService.actionBlocked(p))ci.setReturnValue(InteractionResult.FAIL);}
}
