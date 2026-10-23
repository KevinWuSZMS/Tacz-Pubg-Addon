package com.example.examplemod.zones.match.mixin;

import com.example.examplemod.zones.match.MatchBoundaryService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class MatchBoundaryEntityMixin {
    @ModifyVariable(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 taczPubg$constrainMapExit(Vec3 movement) {
        return MatchBoundaryService.constrain((Entity) (Object) this, movement);
    }
}
