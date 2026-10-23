package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.inventory.MatchAmmoStacks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Inventory.class, priority = 1200)
abstract class MatchAmmoInventoryMixin {
    @Shadow @Final public Player player;

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"))
    private void taczPubg$ordinaryPickup(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (!player.level().isClientSide && !InventoryConfig.active(player)) MatchAmmoStacks.clearMarker(stack);
    }

    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"))
    private void taczPubg$ordinaryReturn(ItemStack stack, boolean packet, CallbackInfo ci) {
        if (!player.level().isClientSide && !InventoryConfig.active(player)) MatchAmmoStacks.clearMarker(stack);
    }

    @Inject(method = "setItem", at = @At("HEAD"))
    private void taczPubg$ordinaryContainerInsertion(int slot, ItemStack stack, CallbackInfo ci) {
        if (!player.level().isClientSide && !InventoryConfig.active(player)) MatchAmmoStacks.clearMarker(stack);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void taczPubg$repairExternalInsertion(CallbackInfo ci) {
        MatchAmmoStacks.normalizeOutsideRound(player);
    }
}
