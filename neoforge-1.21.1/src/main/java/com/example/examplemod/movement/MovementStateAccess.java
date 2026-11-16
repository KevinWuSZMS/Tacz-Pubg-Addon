package com.example.examplemod.movement;

/** Transient, entity-synchronized ground controls. Zero yields ownership to vanilla. */
public interface MovementStateAccess {
    byte taczPubg$movementMode();
    void taczPubg$movementMode(byte value, boolean acknowledge);
}
