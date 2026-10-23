package com.example.examplemod.zones.bombing;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Implemented on the existing TaCZ entity; no new entity ID or client renderer is introduced. */
public interface BombingProjectile {
    void taczPubg$configureBombingProjectile(ResourceLocation ammo, ResourceLocation launcher, Vec3 origin, float damageRadius);
}
