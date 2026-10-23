package com.example.examplemod.gunbalance.client.reload;

import java.util.function.IntPredicate;

import net.minecraft.world.item.ItemStack;

/** Tracks belonging to one reload; unrelated movement or idle tracks cannot clear its snapshot. */
public interface WorkbenchReloadAnimationContext extends WorkbenchReloadAnimationRate {
    ItemStack taczPubg$getReloadAnimationGunItem();
    void taczPubg$beginReloadAnimation(double rate);
    void taczPubg$clearReloadAnimation();
    void taczPubg$bindReloadAnimationTrack(int track);
    void taczPubg$finishReloadAnimationTrack(int track);
    void taczPubg$retireReloadAnimationTracks(IntPredicate finished);
}
