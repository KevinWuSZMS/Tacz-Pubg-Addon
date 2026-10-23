package com.example.examplemod.backup.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Paused maintenance drains entity storage results without ticking the game world. */
@Mixin(ServerLevel.class)
public interface EntityBaselineLevelAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> taczPubg$entityManager();
}
