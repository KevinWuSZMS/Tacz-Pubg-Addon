package com.example.examplemod.backup.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the existing chunk index, avoiding spatial queries whose work can scale with the Y range. */
@Mixin(PersistentEntitySectionManager.class)
public interface EntityBaselineStorageAccessor {
    @Accessor("sectionStorage")
    EntitySectionStorage<Entity> taczPubg$sectionStorage();

    @Accessor("permanentStorage")
    EntityPersistentStorage<Entity> taczPubg$permanentStorage();
}
