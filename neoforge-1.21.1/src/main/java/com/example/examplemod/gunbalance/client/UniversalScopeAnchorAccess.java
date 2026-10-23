package com.example.examplemod.gunbalance.client;

import net.minecraft.world.item.ItemStack;

/** Prepares the same temporary scope mount for TaCZ rendering and its ADS camera positioning. */
public interface UniversalScopeAnchorAccess {
    void taczPubg$prepareScopeAnchor(ItemStack gun);
    void taczPubg$restoreScopeAnchor();
}
