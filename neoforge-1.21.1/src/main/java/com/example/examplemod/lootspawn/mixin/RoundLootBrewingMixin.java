package com.example.examplemod.lootspawn.mixin;

import com.example.examplemod.lootspawn.RoundLootService;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionBrewing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PotionBrewing.class)
public abstract class RoundLootBrewingMixin {
    @Inject(method = "mix", at = @At("RETURN"))
    private void product(ItemStack reagent, ItemStack input, CallbackInfoReturnable<ItemStack> cir) {
        if (cir.getReturnValue() != input) RoundLootService.product(cir.getReturnValue());
    }
}
