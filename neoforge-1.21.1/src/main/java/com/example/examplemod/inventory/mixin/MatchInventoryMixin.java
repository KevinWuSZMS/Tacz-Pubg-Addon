package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.inventory.InventorySettings;
import com.example.examplemod.inventory.MatchInventoryService;
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

@Mixin(value = Inventory.class, priority = 1100)
public abstract class MatchInventoryMixin {
    @Shadow @Final public Player player;
    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void pubg$acquire(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (InventoryConfig.active(player)) ci.setReturnValue(MatchInventoryService.insert(player, stack, true));
    }
    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"), cancellable = true)
    private void pubg$returnOwned(ItemStack stack, boolean packet, CallbackInfo ci) {
        if (!InventoryConfig.active(player)) return;
        if (!player.level().isClientSide) {
            MatchInventoryService.insert(player, stack, false);
            if (!stack.isEmpty()) { player.drop(stack.copy(), false); stack.setCount(0); }
        }
        ci.cancel();
    }
    /** Inventory inherits Container's 99-item ceiling; only round inventories raise it. */
    public int getMaxStackSize() {
        return InventoryConfig.active(player) ? InventorySettings.MAX_AMMO_STACK : 99;
    }
}
