package com.example.examplemod.lootspawn;

import com.example.examplemod.ExampleMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LootSpawnRegistration {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<GroundLootEntity>> GROUND_LOOT =
            ENTITIES.register("ground_loot", () -> EntityType.Builder
                    .<GroundLootEntity>of(GroundLootEntity::new, MobCategory.MISC)
                    .sized(GroundLootFootprints.COLLISION_WIDTH, GroundLootFootprints.COLLISION_HEIGHT).fireImmune().noSummon()
                    .clientTrackingRange(6).updateInterval(1)
                    .build(ExampleMod.MODID + ":ground_loot"));

    private LootSpawnRegistration() {
    }
}
