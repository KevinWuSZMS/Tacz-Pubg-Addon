package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.InventoryConfig;
import com.example.examplemod.inventory.MatchInventoryService;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractContainerMenu.class, priority = 1100)
public abstract class MatchInventoryMenuMixin {
    @Shadow @Final public NonNullList<Slot> slots;
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void pubg$fixedSlots(int slot, int button, ClickType type, Player player, CallbackInfo ci) {
        if (!MatchInventoryService.permitsClick((AbstractContainerMenu) (Object) this, slot, button, type, player)) ci.cancel();
    }
    @Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
    private void pubg$routeTransfer(ItemStack stack, int start, int end, boolean reverse, CallbackInfoReturnable<Boolean> ci) {
        if (stack.isEmpty() || start < 0 || end > slots.size()) return;
        Inventory inventory = null;
        for (int i = start; i < end; i++) if (slots.get(i).container instanceof Inventory found) { inventory = found; break; }
        if (inventory == null || !InventoryConfig.active(inventory.player)) return;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i) == stack) return;
        int before = stack.getCount();
        MatchInventoryService.insert(inventory.player, stack,
                !com.example.examplemod.backpack.BackpackOwnership.isTemporaryInputStack(inventory.player, stack));
        ci.setReturnValue(before > stack.getCount());
    }
    // Shift-click checks empty slots with mayPlace, but merges into existing stacks without it.
    @ModifyExpressionValue(method = "moveItemStackTo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean pubg$canMergeIntoSlot(boolean matches, @Local Slot slot, @Local(argsOnly = true) ItemStack incoming) {
        return matches && MatchInventoryService.mayPlace(slot, incoming);
    }
}
