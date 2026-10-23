package com.example.examplemod.backpack.mixin;

import com.example.examplemod.backpack.BackpackService;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerMenu.class)
public abstract class BackpackMenuMixin {
    @Shadow @Final public NonNullList<Slot> slots;
    @Shadow protected abstract boolean moveItemStackTo(ItemStack stack, int start, int end, boolean reverse);
    @Unique private boolean taczPubg$moving;

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void taczPubg$checkClick(int slot, int button, ClickType type, Player player, CallbackInfo ci) {
        if (!BackpackService.permitsClick((AbstractContainerMenu) (Object) this, slot, button, type, player)) ci.cancel();
    }
    @Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
    private void taczPubg$limitTransfer(ItemStack stack, int start, int end, boolean reverse, CallbackInfoReturnable<Boolean> cir) {
        if (taczPubg$moving || stack.isEmpty() || start < 0 || end > slots.size()) return;
        Inventory inventory = null;
        for (int i = start; i < end; i++) {
            if (slots.get(i).container instanceof Inventory inv) { inventory = inv; break; }
        }
        if (inventory == null || !BackpackService.active(inventory.player)) return;
        // Rearranging owned items does not acquire anything and must work even if a
        // legacy/admin inventory is already overloaded.
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i) == stack) return;
        if (com.example.examplemod.backpack.BackpackOwnership.isTemporaryInputStack(inventory.player, stack)) return;
        int allowed = BackpackService.allowed(inventory.player, stack);
        if (allowed >= stack.getCount()) return;
        if (allowed <= 0) { BackpackService.warn(inventory.player); cir.setReturnValue(false); return; }
        ItemStack limited = stack.copyWithCount(allowed);
        taczPubg$moving = true;
        try {
            moveItemStackTo(limited, start, end, reverse);
            int inserted = allowed - limited.getCount();
            stack.shrink(inserted);
            cir.setReturnValue(inserted > 0);
        } finally { taczPubg$moving = false; }
    }
}
