package com.example.examplemod.lootspawn;

import java.util.List;

/** Pure magazine grouping checks; does not load Minecraft, TaCZ or a game instance. */
public final class AmmoGroupingChecks {
    private static int checks;

    public static void main(String[] args) {
        check(AmmoGrouping.companionPileCount(1) == 1, "one-round capacity still supplies thirty rounds");
        check(AmmoGrouping.companionPileCount(30) == 1, "thirty-round magazine creates one ammo pile");
        check(AmmoGrouping.companionPileCount(31) == 2, "thirty-one rounds creates two piles");
        check(AmmoGrouping.companionPileCount(75) == 3, "seventy-five rounds creates three piles");

        var keep = AmmoGrouping.RemainderPolicy.KEEP_REMAINDER;
        check(AmmoGrouping.split(50, 30, keep, 512).equals(List.of(30, 20)), "50 rounds preserve the final 20");
        check(AmmoGrouping.split(90, 30, keep, 512).equals(List.of(30, 30, 30)), "exact magazines add no empty pile");
        check(AmmoGrouping.split(201, 100, keep, 512).equals(List.of(100, 100, 1)),
                "a magazine above the inventory stack limit stays one logical pile");
        check(AmmoGrouping.split(50, Integer.MAX_VALUE, keep, 512).equals(List.of(50)), "ceil arithmetic does not overflow");
        check(AmmoGrouping.split(0, 30, keep, 0).isEmpty(), "zero quantity needs no carrier budget");
        check(AmmoGrouping.split(512, 1, keep, 512).size() == 512, "exact region budget is legal");
        rejects(() -> AmmoGrouping.split(513, 1, keep, 512), "one over the carrier budget is rejected");
        rejects(() -> AmmoGrouping.split(1, 30, keep, 0), "a nonempty result cannot exceed zero remaining budget");
        rejects(() -> AmmoGrouping.split(-1, 30, keep, 512), "negative totals are rejected");
        rejects(() -> AmmoGrouping.split(1, 0, keep, 512), "zero magazine capacity is rejected");
        rejects(() -> AmmoGrouping.split(1, -1, keep, 512), "negative magazine capacity is rejected");
        rejects(() -> AmmoGrouping.split(1, 30, keep, -1), "negative carrier budgets are rejected");

        for (int total : List.of(0, 1, 29, 30, 31, 50, 60, 90, 4096)) {
            for (int capacity : List.of(1, 5, 17, 30, 64, 100, 4096, Integer.MAX_VALUE)) {
                List<Integer> piles = AmmoGrouping.split(total, capacity, keep, 4096);
                check(piles.stream().mapToLong(Integer::longValue).sum() == total, "grouping never invents or loses rounds");
                check(piles.stream().allMatch(quantity -> quantity >= 1 && quantity <= capacity), "each pile fits its magazine");
                check(piles.size() == (total + (long) capacity - 1) / capacity, "only the necessary piles are created");
            }
        }
        System.out.println("AmmoGroupingChecks: " + checks + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }
}
