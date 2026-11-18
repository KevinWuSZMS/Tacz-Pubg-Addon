package com.example.examplemod.inventory.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserve the dispatcher's existing shadow setting around the native inventory helper. */
@Mixin(EntityRenderDispatcher.class)
public interface InventoryEntityRenderDispatcherAccessor {
    @Accessor("shouldRenderShadow")
    boolean taczPubg$shouldRenderShadow();
}
