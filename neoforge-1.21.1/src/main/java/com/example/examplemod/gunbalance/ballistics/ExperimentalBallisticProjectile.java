package com.example.examplemod.gunbalance.ballistics;

/** Per-projectile mode is fixed at launch and is not switched while the projectile is airborne. */
public interface ExperimentalBallisticProjectile {
    void taczPubg$lockExperimentalBallistics(boolean enabled);
    boolean taczPubg$usesExperimentalBallistics();
}
