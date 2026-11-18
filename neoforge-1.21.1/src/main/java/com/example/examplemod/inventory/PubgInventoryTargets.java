package com.example.examplemod.inventory;

import java.util.function.IntPredicate;

/** Same deterministic gun preference for server actions and client hover previews. */
public final class PubgInventoryTargets {
    private PubgInventoryTargets() { }

    public static int[] priority(int selected) {
        return switch (selected) {
            case 1 -> new int[] {1, 0, 2};
            case 2 -> new int[] {2, 0, 1};
            default -> new int[] {0, 1, 2};
        };
    }

    /** Select once. A rejected transaction must not silently modify a different gun. */
    public static int firstCandidate(int selected, IntPredicate eligible) {
        for (int slot : priority(selected)) if (eligible.test(slot)) return slot;
        return -1;
    }
}
