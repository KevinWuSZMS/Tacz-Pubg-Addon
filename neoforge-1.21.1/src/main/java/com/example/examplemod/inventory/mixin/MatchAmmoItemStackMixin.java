package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.MatchAmmoStacks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
abstract class MatchAmmoItemStackMixin {
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;lazyInitialized(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;",
            ordinal = 0))
    private static Codec<ItemStack> taczPubg$saveLargeAmmo(Codec<ItemStack> original) {
        return MatchAmmoStacks.wrapCodec(original);
    }

    @Inject(method = "getMaxStackSize", at = @At("RETURN"), cancellable = true)
    private void taczPubg$matchAmmoLimit(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(MatchAmmoStacks.maximum((ItemStack) (Object) this, cir.getReturnValue()));
    }
}
