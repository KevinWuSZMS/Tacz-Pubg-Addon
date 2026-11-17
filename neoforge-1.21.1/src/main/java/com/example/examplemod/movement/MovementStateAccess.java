package com.example.examplemod.movement;

/** Transient, entity-synchronized ground controls and match participation for remote swimming. */
public interface MovementStateAccess {
    byte taczPubg$movementMode();
    void taczPubg$movementMode(byte value, boolean acknowledge);
    boolean taczPubg$matchParticipant();
    void taczPubg$matchParticipant(boolean value);
}
