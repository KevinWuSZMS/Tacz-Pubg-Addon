package com.example.examplemod.movement;

import com.example.examplemod.movement.MovementState.Mode;
import java.util.Objects;

/** Stable wire/metadata IDs, independent of enum declaration order. */
public final class MovementModes {
    private MovementModes() { }

    public static byte encode(Mode mode, boolean active) {
        Objects.requireNonNull(mode, "mode");
        if (!active) return 0;
        return switch (mode) {
            case STAND -> 1;
            case CROUCH -> 2;
            case PRONE -> 3;
            case SPRINT -> 4;
            case WALK -> 5;
        };
    }

    public static Mode decode(int code) {
        return switch (code) {
            case 0, 1 -> Mode.STAND;
            case 2 -> Mode.CROUCH;
            case 3 -> Mode.PRONE;
            case 4 -> Mode.SPRINT;
            case 5 -> Mode.WALK;
            default -> throw new IllegalArgumentException("Unknown ground movement mode: " + code);
        };
    }

    public static byte authorize(Mode requested, boolean active, boolean eligible) {
        return encode(requested, active && eligible);
    }

    /** A cleared input context must also release a sprint previously owned by these controls. */
    public static boolean stopsSprint(byte previous, byte next) {
        return next != 0 && decode(next) != Mode.SPRINT || previous != 0 && next == 0;
    }
}
