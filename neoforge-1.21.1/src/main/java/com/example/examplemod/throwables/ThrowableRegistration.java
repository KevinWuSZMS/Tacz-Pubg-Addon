package com.example.examplemod.throwables;

import com.example.examplemod.ExampleMod;
import java.util.EnumMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ThrowableRegistration {
    public static final EnumMap<ThrowableKind, DeferredItem<PubgThrowableItem>> ITEMS = new EnumMap<>(ThrowableKind.class);
    static {
        for (ThrowableKind kind : ThrowableKind.values()) ITEMS.put(kind,
                ExampleMod.ITEMS.registerItem(kind.id, p -> new PubgThrowableItem(p, kind), new Item.Properties().stacksTo(8)));
    }
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<PubgThrowableEntity>> THROWABLE =
            ENTITIES.register("pubg_throwable", () -> EntityType.Builder
                    .<PubgThrowableEntity>of(PubgThrowableEntity::new, MobCategory.MISC)
                    .sized(.25f, .25f).fireImmune().clientTrackingRange(10).updateInterval(1)
                    .build(ExampleMod.MODID + ":pubg_throwable"));
    public static final DeferredHolder<EntityType<?>, EntityType<PubgSmokeGrenadeEntity>> SMOKE =
            ENTITIES.register("pubg_smoke_grenade", () -> EntityType.Builder
                    .<PubgSmokeGrenadeEntity>of(PubgSmokeGrenadeEntity::new, MobCategory.MISC)
                    .sized(.3f, .3f).fireImmune().clientTrackingRange(10).updateInterval(1)
                    .build(ExampleMod.MODID + ":pubg_smoke_grenade"));
    public static void registerItems() {}
    private ThrowableRegistration() {}
}
