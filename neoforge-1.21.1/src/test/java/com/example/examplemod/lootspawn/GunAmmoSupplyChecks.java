package com.example.examplemod.lootspawn;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.AmmoOption;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Whole-plan sampling and dynamic native ammo; no worlds, entities, or resource reloads. */
public final class GunAmmoSupplyChecks {
    private static int checks;
    private static final String[] AMMO = {"test:first", "test:second", "test:third"};
    private static final Set<String> LOADED = Set.of(AMMO[0], AMMO[1], AMMO[2], "native:old", "native:applied");

    private GunAmmoSupplyChecks() {}

    public static void main(String[] args) {
        completePlanChoicesAndQuantities();
        defaultPlans();
        selfAndExplicitResources();
        zeroProbabilitiesAndBoundaries();
        invalidSettingsAndMissingResources();
        repeatableDistribution();
        System.out.println("Gun ammo supply checks passed: " + checks);
    }

    private static void completePlanChoicesAndQuantities() {
        int[] groups = {2, 17, LootConfigModel.MAX_AMMO_GROUPS};
        double[] draws = {.1, .7, .95};
        for (int rounds : new int[]{1, 3, 10, 15, 30, LootConfigModel.MAX_AMMO_ROUNDS_PER_GROUP}) {
            GunAmmoSupply authored = explicit(rounds);
            for (int option = 0; option < 3; option++) {
                FixedRandom random = new FixedRandom(draws[option]);
                var selected = roll(authored, random);
                check(selected.ammoId().equals(AMMO[option]), "single roll selects the authored ammo asset");
                check(selected.groups() == groups[option], "ammo and group count belong to the same complete plan");
                check(selected.roundsPerGroup() == rounds, "authored quantity never uses magazine capacity");
                check(random.calls == 1, "one actual gun consumes exactly one random value");
            }
        }
    }

    private static void defaultPlans() {
        for (boolean rifleFamily : new boolean[]{false, true}) {
            double[] probabilities = rifleFamily ? new double[]{.3, .6, .1} : new double[]{.6, .3, .1};
            double[] draws = rifleFamily ? new double[]{.1, .5, .95} : new double[]{.1, .7, .95};
            for (int rounds : new int[]{1, 3, 10, 15, 30}) {
                GunAmmoSupply defaults = GunAmmoSupply.defaults(rounds, rifleFamily);
                check(defaults.ammoOptions().size() == 3 && defaults.roundsPerGroup() == rounds,
                        "defaults contain exactly three plans and the gun-specific quantity");
                for (int option = 0; option < 3; option++) {
                    AmmoOption configured = defaults.ammoOptions().get(option);
                    check(configured.ammoId().equals("self") && configured.groups() == option + 1
                            && configured.probability() == probabilities[option],
                            "default self plans use family-specific probability and groups one/two/three");
                    FixedRandom random = new FixedRandom(draws[option]);
                    var selected = roll(defaults, random);
                    check(selected.ammoId().equals("native:applied") && selected.groups() == option + 1
                            && selected.roundsPerGroup() == rounds && random.calls == 1,
                            "default whole-plan roll preserves correlated quantity in one draw");
                }
            }
        }
        check(GunAmmoSupply.defaults(15).equals(GunAmmoSupply.defaults(15, false)),
                "single-argument default remains the other-gun family adapter");
    }

    private static void selfAndExplicitResources() {
        AtomicReference<String> applied = new AtomicReference<>("native:old");
        AtomicInteger selfCalls = new AtomicInteger(), parserCalls = new AtomicInteger();
        GunAmmoSupply self = GunAmmoSupply.defaults(15);
        Function<String, String> lookup = gun -> { selfCalls.incrementAndGet(); return applied.get(); };
        Function<String, String> parser = id -> { parserCalls.incrementAndGet(); return id; };
        var original = LootGunAmmoLinks.roll("gun", self, lookup, parser, LOADED::contains, new FixedRandom(.95));
        check(original.ammoId().equals("native:old") && original.groups() == 3, "self reads applied original resources");
        check(selfCalls.get() == 1 && parserCalls.get() == 0, "three groups share one self lookup and no parser call");
        applied.set("native:applied");
        var experimental = LootGunAmmoLinks.roll("gun", self, lookup, parser, LOADED::contains, new FixedRandom(.95));
        check(experimental.ammoId().equals("native:applied"), "authored self follows a completed native experiment switch");
        check(original.ammoId().equals("native:old"), "an already selected plan stays fixed for all its groups");

        GunAmmoSupply explicit = new GunAmmoSupply(List.of(new AmmoOption("native:old", 64, 1),
                new AmmoOption("self", 1, 0), new AmmoOption("self", 3, 0)), 10);
        selfCalls.set(0); parserCalls.set(0);
        FixedRandom random = new FixedRandom(.99);
        var unchanged = LootGunAmmoLinks.roll("gun", explicit, lookup, parser, LOADED::contains, random);
        check(unchanged.ammoId().equals("native:old") && unchanged.groups() == 64 && random.calls == 1,
                "explicit original ID and authored 64 groups survive the native ammo remap");
        check(selfCalls.get() == 0 && parserCalls.get() == 1, "explicit choice never consults self or remaps by gun");
    }

    private static void zeroProbabilitiesAndBoundaries() {
        GunAmmoSupply onlyMiddle = new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], 1, 0),
                new AmmoOption(AMMO[1], 9, 1), new AmmoOption(AMMO[2], 64, 0)), 30);
        for (double draw : new double[]{0, .3, .6, .9, Math.nextDown(1D)}) {
            var selected = roll(onlyMiddle, new FixedRandom(draw));
            check(selected.ammoId().equals(AMMO[1]) && selected.groups() == 9,
                    "zero-probability leading and trailing plans never revive");
        }
        var second = roll(explicit(30), new FixedRandom(.6));
        check(second.ammoId().equals(AMMO[1]) && second.groups() == 17, "first probability boundary selects the next whole plan");
        var third = roll(explicit(30), new FixedRandom(.9));
        check(third.ammoId().equals(AMMO[2]) && third.groups() == 64, "second boundary keeps third ammo and groups correlated");
        GunAmmoSupply withinTolerance = new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], 1, .6),
                new AmmoOption(AMMO[1], 7, .4 - 5e-10), new AmmoOption(AMMO[2], 64, 0)), 15);
        var selected = roll(withinTolerance, new FixedRandom(Math.nextDown(1D)));
        check(selected.ammoId().equals(AMMO[1]) && selected.groups() == 7,
                "accepted sum tolerance cannot fall into a disabled last plan");
    }

    private static void invalidSettingsAndMissingResources() {
        FixedRandom invalid = new FixedRandom();
        rejects(() -> roll(null, invalid), "runtime caller must expand null into a gun-specific default");
        check(invalid.calls == 0, "invalid settings consume no randomness");
        List<AmmoOption> valid = explicit(30).ammoOptions();
        for (int rounds : new int[]{0, -1, LootConfigModel.MAX_AMMO_ROUNDS_PER_GROUP + 1})
            rejects(() -> roll(new GunAmmoSupply(valid, rounds), new FixedRandom()), "invalid rounds rejected before sampling");
        for (double probability : new double[]{-.1, Double.NaN, Double.POSITIVE_INFINITY, 1.1})
            rejects(() -> roll(new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], 1, probability),
                    new AmmoOption(AMMO[1], 2, .3), new AmmoOption(AMMO[2], 3, .1)), 30), new FixedRandom()),
                    "invalid probabilities rejected");
        for (int groups : new int[]{0, -1, LootConfigModel.MAX_AMMO_GROUPS + 1})
            rejects(() -> roll(new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], groups, 1),
                    new AmmoOption(AMMO[1], 2, 0), new AmmoOption(AMMO[2], 3, 0)), 30), new FixedRandom()),
                    "each plan group count must be one to sixty-four");
        rejects(() -> roll(new GunAmmoSupply(valid.subList(0, 2), 30), new FixedRandom()), "exactly three plans required");
        rejects(() -> roll(new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], 1, .2),
                new AmmoOption(AMMO[1], 2, .3), new AmmoOption(AMMO[2], 3, .1)), 30), new FixedRandom()),
                "plan probabilities must total one");
        rejects(() -> roll(new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], 1, 0),
                new AmmoOption(AMMO[1], 2, 0), new AmmoOption(AMMO[2], 3, 0)), 30), new FixedRandom()),
                "all-zero plans are invalid");

        FixedRandom missingSelf = new FixedRandom(.1);
        rejects(() -> LootGunAmmoLinks.roll("gun", GunAmmoSupply.defaults(30), gun -> null,
                Function.identity(), LOADED::contains, missingSelf), "missing self fails rather than inventing supply");
        check(missingSelf.calls == 1, "missing self is not retried with another plan");
        rejects(() -> LootGunAmmoLinks.roll("gun", GunAmmoSupply.defaults(30), gun -> "missing:self",
                Function.identity(), LOADED::contains, new FixedRandom(.1)), "self must pass the loaded-resource predicate");
        FixedRandom missingExplicit = new FixedRandom(.7);
        rejects(() -> LootGunAmmoLinks.roll("gun", explicit(30), gun -> "native:applied",
                Function.identity(), id -> !id.equals(AMMO[1]), missingExplicit), "missing explicit ammo aborts instead of remapping");
        check(missingExplicit.calls == 1, "missing explicit is never retried as another plan");
        rejects(() -> LootGunAmmoLinks.roll("gun", explicit(30), gun -> "native:applied",
                id -> null, LOADED::contains, new FixedRandom(.7)), "failed explicit parser aborts");
    }

    private static void repeatableDistribution() {
        Random first = new Random(20261019L), second = new Random(20261019L);
        GunAmmoSupply supply = explicit(3);
        for (int i = 0; i < 100; i++)
            check(roll(supply, first).equals(roll(supply, second)), "same job seed and snapshot reproduce whole plans");
        int[] observed = new int[3];
        Random random = new Random(910192026L);
        int samples = 30_000;
        for (int i = 0; i < samples; i++) {
            var selected = roll(supply, random);
            int option = List.of(AMMO).indexOf(selected.ammoId());
            observed[option]++;
            int expectedGroups = option == 0 ? 2 : option == 1 ? 17 : 64;
            if (selected.groups() != expectedGroups) throw new AssertionError("Plan ammo/group correlation broken");
        }
        double[] probabilities = {.6, .3, .1};
        for (int option = 0; option < 3; option++)
            check(Math.abs(observed[option] / (double) samples - probabilities[option]) < .02,
                    "whole-plan frequencies follow the authored distribution");
    }

    private static GunAmmoSupply explicit(int rounds) {
        return new GunAmmoSupply(List.of(new AmmoOption(AMMO[0], 2, .6), new AmmoOption(AMMO[1], 17, .3),
                new AmmoOption(AMMO[2], 64, .1)), rounds);
    }

    private static LootGunAmmoLinks.Supply<String> roll(GunAmmoSupply supply, Random random) {
        return LootGunAmmoLinks.roll("gun", supply, gun -> "native:applied", Function.identity(), LOADED::contains, random);
    }

    private static final class FixedRandom extends Random {
        private final double[] values;
        private int calls;
        private FixedRandom(double... values) { this.values = values; }
        @Override public double nextDouble() {
            if (calls >= values.length) throw new AssertionError("Unexpected second random draw");
            return values[calls++];
        }
    }

    private static void rejects(Runnable action, String reason) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + reason);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
}