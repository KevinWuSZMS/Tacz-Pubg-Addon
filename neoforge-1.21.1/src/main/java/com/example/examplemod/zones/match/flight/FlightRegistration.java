package com.example.examplemod.zones.match.flight;

import com.example.examplemod.ExampleMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FlightRegistration {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<TransportFormationEntity>> TRANSPORT =
            ENTITIES.register("transport_formation", () -> EntityType.Builder
                    .<TransportFormationEntity>of(TransportFormationEntity::new, MobCategory.MISC)
                    .sized(1, 1).fireImmune().noSave().noSummon().clientTrackingRange(16).updateInterval(1)
                    .build(ExampleMod.MODID + ":transport_formation"));
    private FlightRegistration() {}
}
