package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchDropService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also covers non-death mob drops, such as eggs and wool, without filtering player drops. */
@Mixin(Entity.class)
public abstract class MatchMobDropsMixin {
    @Inject(method = "spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void taczPubg$mobItem(ItemStack stack, float offsetY, CallbackInfoReturnable<ItemEntity> cir) {
        if (MatchDropService.blocksMobDrops((Entity) (Object) this)) cir.setReturnValue(null);
    }
}
