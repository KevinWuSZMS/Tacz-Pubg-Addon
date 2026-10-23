package com.example.examplemod.gunbalance.reload;

import net.minecraft.resources.ResourceLocation;

/** Per-shooter snapshot, fixed at reload start even if refitting changes the held stack. */
public interface WorkbenchReloadRate {
    double taczPubg$getMagazineReloadRate();
    void taczPubg$setMagazineReloadRate(double rate);
    ResourceLocation taczPubg$getReloadGunId();
    ResourceLocation taczPubg$getReloadMagazineId();
    void taczPubg$setReloadMagazineSelection(ResourceLocation gunId, ResourceLocation magazineId);
}
