package com.example.examplemod.lootspawn.mixin;

import com.example.examplemod.lootspawn.RoundLootService;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultContainer.class)
public abstract class RoundLootProductMixin {
    @Inject(method = "setItem", at = @At("HEAD"))
    private void product(int slot, ItemStack result, CallbackInfo ci) { RoundLootService.product(result); }
}
