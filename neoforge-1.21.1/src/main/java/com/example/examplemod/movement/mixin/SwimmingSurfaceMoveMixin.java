package com.example.examplemod.movement.mixin;

import com.example.examplemod.movement.MatchSwimming;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Apply after native water thrust, immediately before the actual collision-aware move. */
@Mixin(Entity.class)
abstract class SwimmingSurfaceMoveMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 taczPubg$keepBodyInWater(Vec3 movement, MoverType type, Vec3 original) {
        if (type != MoverType.SELF || !((Object) this instanceof Player player)) return movement;
        Vec3 limited = MatchSwimming.limitSurfaceRise(player, movement);
        if (limited.y != movement.y && player.getDeltaMovement().y > limited.y) {
            Vec3 velocity = player.getDeltaMovement();
            player.setDeltaMovement(velocity.x, limited.y, velocity.z);
        }
        return limited;
    }
}
