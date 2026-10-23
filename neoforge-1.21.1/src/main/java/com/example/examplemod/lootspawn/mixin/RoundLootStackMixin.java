package com.example.examplemod.lootspawn.mixin;

import com.example.examplemod.lootspawn.RoundLootService;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class RoundLootStackMixin {
    @Shadow @Final private Item item;
    @Shadow @Final private PatchedDataComponentMap components;
    @Shadow private int count;
    @Inject(method = "isEmpty", at = @At("HEAD"), cancellable = true)
    private void expiredRound(CallbackInfoReturnable<Boolean> cir) {
        var type = RoundLootService.MARK;
        if (count > 0 && type != null && components != null && RoundLootService.expired(components.get(type), item)) {
            count = 0; // Permanent once seen, including if the tombstone cache is later cleared.
            cir.setReturnValue(true);
        }
    }
}
