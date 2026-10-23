package com.example.examplemod.vitals.mixin;

import com.example.examplemod.vitals.MatchVitalsService;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Peaceful difficulty has a second natural-healing path outside FoodData.tick. */
@Mixin(Player.class)
public abstract class MatchPeacefulHealingMixin {
    @Redirect(method = "aiStep()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"))
    private void taczPubg$skipMatchPeacefulHealing(Player player, float amount) {
        if (!MatchVitalsService.freezeHunger((Player) (Object) this)) player.heal(amount);
    }
}
