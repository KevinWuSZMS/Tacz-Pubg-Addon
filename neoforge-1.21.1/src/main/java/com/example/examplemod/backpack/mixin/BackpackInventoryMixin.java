package com.example.examplemod.backpack.mixin;

import com.example.examplemod.backpack.BackpackService;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class BackpackInventoryMixin {
    @Shadow @Final public Player player;
    @Unique private boolean taczPubg$inserting;
    @Unique private int taczPubg$returning;

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void taczPubg$limitPickup(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (taczPubg$inserting || taczPubg$returning > 0 || stack.isEmpty() || !BackpackService.active(player)) return;
        int allowed = BackpackService.allowed(player, stack);
        if (allowed >= stack.getCount()) return;
        if (allowed <= 0) {
            BackpackService.warn(player);
            cir.setReturnValue(false);
            return;
        }
        ItemStack limited = stack.copyWithCount(allowed);
        taczPubg$inserting = true;
        try {
            ((Inventory) (Object) this).add(slot, limited);
            int inserted = allowed - limited.getCount();
            stack.shrink(inserted);
            // Inventory.add's contract is false while any remainder survives.
            // Callers such as number-key swaps must still drop/return that remainder.
            cir.setReturnValue(stack.isEmpty());
        } finally { taczPubg$inserting = false; }
    }
    // Vanilla's return helper splits stacks before calling add and discards rejected
    // remainders. Already-owned cursor/crafting returns must bypass the acquisition cap.
    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"))
    private void taczPubg$beginReturn(ItemStack stack, boolean sendPacket, CallbackInfo ci) { taczPubg$returning++; }
    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("RETURN"))
    private void taczPubg$endReturn(ItemStack stack, boolean sendPacket, CallbackInfo ci) { taczPubg$returning--; }
}
