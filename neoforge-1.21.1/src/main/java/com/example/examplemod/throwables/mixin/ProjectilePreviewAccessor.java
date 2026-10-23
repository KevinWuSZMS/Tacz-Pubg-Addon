package com.example.examplemod.throwables.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Allows only the unspawned preview to reuse vanilla's same-vehicle / left-owner collision rule. */
@Mixin(Projectile.class)
public interface ProjectilePreviewAccessor {
    @Accessor("leftOwner") boolean pubg$getLeftOwner();
    @Accessor("leftOwner") void pubg$setLeftOwner(boolean value);
    @Invoker("checkLeftOwner") boolean pubg$checkLeftOwner();
}
