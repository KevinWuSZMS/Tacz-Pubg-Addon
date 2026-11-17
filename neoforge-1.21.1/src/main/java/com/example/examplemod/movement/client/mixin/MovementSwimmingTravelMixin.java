package com.example.examplemod.movement.client.mixin;

import com.example.examplemod.movement.client.MovementClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Manual vertical controls keep their direction even while looking the other way. */
@Mixin(Player.class)
abstract class MovementSwimmingTravelMixin {
    @Redirect(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isSwimming()Z"))
    private boolean taczPubg$manualVerticalOverridesLook(Player player) {
        return player.isSwimming() && !(player == Minecraft.getInstance().player && MovementClient.manualSwimmingVertical());
    }
}
