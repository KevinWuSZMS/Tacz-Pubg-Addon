package com.example.examplemod.lootspawn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Pure optional attachment bundle checks; no Minecraft process or world is required. */
public final class AttachmentCompanionChecks {
    private static int checks;

    public static void main(String[] args) {
        List<String> pool = List.of("stock:a", "muzzle:b", "grip:c", "stock:d");
        CountingRandom zero = new CountingRandom(0);
        check(AttachmentCompanionSelection.select(pool, 0, 2, zero).isEmpty(), "chance zero disables bundles");
        check(zero.rolls == 0 && zero.picks == 0, "disabled chance consumes no random draws");
        check(AttachmentCompanionSelection.select(pool, 1, 0, zero).isEmpty(), "zero kinds disables bundles");
        check(AttachmentCompanionSelection.select(List.of(), 1, 2, zero).isEmpty(), "no candidates produces nothing");
        check(zero.rolls == 0 && zero.picks == 0, "disabled kinds and empty candidates consume no draws");

        CountingRandom rejected = new CountingRandom(0.8);
        check(AttachmentCompanionSelection.select(pool, 0.5, 3, rejected).isEmpty(), "failed probability roll adds nothing");
        check(rejected.rolls == 1 && rejected.picks == 0, "failed bundle never retries its probability roll");
        CountingRandom accepted = new CountingRandom(0.49);
        List<String> bundle = AttachmentCompanionSelection.select(pool, 0.5, 3, accepted);
        check(bundle.size() == 3 && new HashSet<>(bundle).size() == 3, "successful bundle contains three different kinds");
        check(accepted.rolls == 1 && accepted.picks == 3, "successful bundle rolls probability only once");
        CountingRandom boundary = new CountingRandom(0.5);
        check(AttachmentCompanionSelection.select(pool, 0.5, 2, boundary).isEmpty(), "chance boundary is exclusive");

        List<String> duplicated = new ArrayList<>(List.of("a", "a", "b", "a", "b"));
        List<String> original = List.copyOf(duplicated);
        List<String> shortage = AttachmentCompanionSelection.select(duplicated, 1, 16, new Random(123));
        check(new HashSet<>(shortage).equals(new HashSet<>(List.of("a", "b"))) && shortage.size() == 2,
                "shortage takes every eligible kind once despite duplicate inputs");
        check(duplicated.equals(original), "candidate lists are never modified");
        check(AttachmentCompanionSelection.select(pool, 1, 3, new Random(9876))
                        .equals(AttachmentCompanionSelection.select(pool, 1, 3, new Random(9876))),
                "the region RNG seed deterministically selects the same bundle");

        for (int seed = 0; seed < 2000; seed++) {
            List<String> selected = AttachmentCompanionSelection.select(pool, 1, 2, new Random(seed));
            check(selected.size() == 2 && new HashSet<>(selected).size() == 2 && pool.containsAll(selected),
                    "sampling never repeats a kind or creates an unsupported candidate");
        }
        rejects(() -> AttachmentCompanionSelection.select(pool, -0.01, 1, new Random()), "negative chance rejected");
        rejects(() -> AttachmentCompanionSelection.select(pool, 1.01, 1, new Random()), "chance above one rejected");
        rejects(() -> AttachmentCompanionSelection.select(pool, Double.NaN, 1, new Random()), "NaN chance rejected");
        rejects(() -> AttachmentCompanionSelection.select(pool, Double.POSITIVE_INFINITY, 1, new Random()), "infinite chance rejected");
        rejects(() -> AttachmentCompanionSelection.select(pool, 1, -1, new Random()), "negative kinds rejected");
        System.out.println("AttachmentCompanionChecks: " + checks + " assertions passed");
    }

    private static final class CountingRandom extends Random {
        private final double result;
        int rolls;
        int picks;
        CountingRandom(double result) { this.result = result; }
        @Override public double nextDouble() { rolls++; return result; }
        @Override public int nextInt(int bound) { picks++; return bound - 1; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
}
