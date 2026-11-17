package com.example.examplemod.movement;

import java.util.EnumSet;
import java.util.Objects;

/** Input-edge reducer: an older held action never reasserts itself over a newer action. */
public final class MovementState {
    public enum Mode { STAND, CROUCH, PRONE, SPRINT, WALK }
    public enum Action { STAND, SPRINT, CROUCH, PRONE, WALK }
    private Mode mode = Mode.STAND;
    private boolean consumeJump;
    private final EnumSet<Action> held = EnumSet.noneOf(Action.class);

    public Mode mode() { return mode; }
    public boolean consumeJump() { return consumeJump; }
    public boolean held(Action action) { return held.contains(action); }
    public boolean anyHeld() { return !held.isEmpty(); }

    /** Keep existing key latches: an old held key must not undo the server's rescue crouch. */
    public void adoptRescueCrouch() { mode = Mode.CROUCH; consumeJump = false; }

    /** Apply an environment restriction without inventing releases or reviving older held actions. */
    public boolean constrainMode(Mode legalMode) {
        Objects.requireNonNull(legalMode, "legalMode");
        if (mode == legalMode) return false;
        mode = legalMode;
        consumeJump = false;
        return true;
    }

    public void press(Action action) { press(action, false); }
    public void press(Action action, boolean externallyLowered) {
        if (!held.add(action)) return;
        switch (action) {
            case STAND -> {
                if (mode == Mode.CROUCH || mode == Mode.PRONE || externallyLowered) {
                    mode = Mode.STAND;
                    consumeJump = true;
                }
            }
            case SPRINT -> mode = Mode.SPRINT;
            case WALK -> mode = Mode.WALK;
            case CROUCH -> mode = mode == Mode.CROUCH ? Mode.STAND : Mode.CROUCH;
            case PRONE -> mode = mode == Mode.PRONE ? Mode.STAND : Mode.PRONE;
        }
    }

    public void release(Action action) {
        held.remove(action);
        if (action == Action.STAND) consumeJump = false;
        if (action == Action.SPRINT && mode == Mode.SPRINT || action == Action.WALK && mode == Mode.WALK) mode = Mode.STAND;
    }

    /** Losing the input context clears both stance and all press/release latches. */
    public void reset() { mode = Mode.STAND; consumeJump = false; held.clear(); }
}
