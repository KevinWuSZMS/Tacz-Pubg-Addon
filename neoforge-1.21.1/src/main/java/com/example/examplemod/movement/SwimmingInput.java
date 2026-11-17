package com.example.examplemod.movement;

/** Swimming actions are physical holds, independent of the ground stance toggle. */
public record SwimmingInput(int vertical, boolean fast, boolean manualVertical) {
    public static SwimmingInput resolve(boolean accepts, boolean up, boolean down, boolean sprint, float forward) {
        if (!accepts) return new SwimmingInput(0, false, false);
        return new SwimmingInput(up == down ? 0 : up ? 1 : -1, sprint && forward > 0, up || down);
    }
}
