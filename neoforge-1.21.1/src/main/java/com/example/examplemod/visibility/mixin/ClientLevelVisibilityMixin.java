package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.VisibilityEntities;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps native interpolation on newly paired far carrier trees without a second movement protocol. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelVisibilityMixin {
    @Shadow @Final private EntityTickList tickingEntities;
    @Unique private final Set<Entity> pubg$managed = Collections.newSetFromMap(new IdentityHashMap<>());
    @Unique private final Set<Entity> pubg$nextManaged = Collections.newSetFromMap(new IdentityHashMap<>());
    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void pubg$reconcileNativeTickList(CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        pubg$nextManaged.clear();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || !VisibilityEntities.managed(entity)) continue;
            pubg$nextManaged.add(entity);
            // Pairing creates a carrier before its passenger packet, so addEntity can miss this eligibility.
            if (!tickingEntities.contains(entity)) tickingEntities.add(entity);
        }
        for (Entity previous : pubg$managed) {
            if (previous.isRemoved() || pubg$nextManaged.contains(previous)) continue;
            boolean nativeTick = previous.isAlwaysTicking()
                    || level.getChunkSource().hasChunk(previous.chunkPosition().x, previous.chunkPosition().z);
            if (nativeTick) {
                if (!tickingEntities.contains(previous)) tickingEntities.add(previous);
            } else if (tickingEntities.contains(previous)) tickingEntities.remove(previous);
        }
        pubg$managed.clear(); pubg$managed.addAll(pubg$nextManaged);
        // All motion, interpolation, frozen-time checks and recursive riding are handled by native tickEntities.
    }
    @WrapOperation(method = "tickPassenger", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;contains(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean pubg$tickManagedIntermediateCarrier(EntityTickList list, Entity rider, Operation<Boolean> original) {
        return VisibilityEntities.managed(rider) || original.call(list, rider);
    }
}
