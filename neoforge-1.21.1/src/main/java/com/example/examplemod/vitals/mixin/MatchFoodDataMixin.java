package com.example.examplemod.vitals.mixin;

import com.example.examplemod.vitals.MatchVitalsService;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps food mechanics local to the server-confirmed active match participant. */
@Mixin(FoodData.class)
public abstract class MatchFoodDataMixin {
    @Shadow private int tickTimer;
    @Shadow private int lastFoodLevel;

    @Inject(method = "tick(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true)
    private void taczPubg$freezeMatchFood(Player player, CallbackInfo callback) {
        if (!MatchVitalsService.freezeHunger(player)) return;
        tickTimer = 0;
        lastFoodLevel = 20;
        callback.cancel();
    }
}
