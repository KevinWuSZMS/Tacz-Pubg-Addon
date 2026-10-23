package com.example.examplemod.lootspawn;

import com.example.examplemod.localization.Texts;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

/** Roll one of three complete ammunition plans per actually generated gun. */
public final class LootGunAmmoLinks {
    /** Legacy capacity-based resolver remains only for pre-existing native-ammo regression checks. */
    public static final int ROUNDS_PER_PILE = 30;

    public record Companion<A>(A ammoId, int baseMagazineCapacity, int piles, int roundsPerPile) {}

    /** All groups for this gun share this one resolved asset and this one authored quantity. */
    public record Supply<A>(A ammoId, int groups, int roundsPerGroup) {
        public Supply {
            Objects.requireNonNull(ammoId, "ammoId");
            if (groups < 1 || groups > LootConfigModel.MAX_AMMO_GROUPS || roundsPerGroup < 1
                    || roundsPerGroup > LootConfigModel.MAX_AMMO_ROUNDS_PER_GROUP)
                throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_gun_ammo_links.invalid_per_gun_ammo_group_count_rounds_per"));
        }
    }

    private LootGunAmmoLinks() {}

    /**
     * The caller supplies the captured document's authored settings or its gun-specific dynamic default.
     * Only "self" consults the current applied native gun index. Explicit IDs are never remapped.
     * A single random choice jointly determines the ammunition asset and the plan's group count.
     */
    public static <G, A> Supply<A> roll(G gun, GunAmmoSupply supply, Function<G, A> loadedSelfAmmoLookup,
            Function<String, A> explicitAmmoParser, Predicate<A> loadedAmmoExists, RandomGenerator random) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(loadedSelfAmmoLookup, "loadedSelfAmmoLookup");
        Objects.requireNonNull(explicitAmmoParser, "explicitAmmoParser");
        Objects.requireNonNull(loadedAmmoExists, "loadedAmmoExists");
        Objects.requireNonNull(random, "random");
        List<String> errors = LootConfigModel.validateGunAmmoSupply(supply);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));

        int ammoChoice = weightedIndex(supply.ammoOptions().stream().map(option -> option.probability()).toList(), random);
        var option = supply.ammoOptions().get(ammoChoice);
        String authoredId = option.ammoId();
        A ammo = "self".equals(authoredId) ? loadedSelfAmmoLookup.apply(gun) : explicitAmmoParser.apply(authoredId);
        if (ammo == null || !loadedAmmoExists.test(ammo))
            throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_gun_ammo_links.selected_gun_ammo_resource_not_loaded_region_was", authoredId));
        return new Supply<>(ammo, option.groups(), supply.roundsPerGroup());
    }

    private static int weightedIndex(List<Double> probabilities, RandomGenerator random) {
        // The validated sum is within tolerance of one. Normalizing avoids a floating-point gap
        // while keeping a zero-probability option impossible, even when it is the last option.
        double total = probabilities.stream().mapToDouble(Double::doubleValue).sum();
        double remaining = random.nextDouble() * total;
        int lastPositive = -1;
        for (int i = 0; i < probabilities.size(); i++) {
            double probability = probabilities.get(i);
            if (probability <= 0) continue;
            lastPositive = i;
            if (remaining < probability) return i;
            remaining -= probability;
        }
        if (lastPositive < 0) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_gun_ammo_links.no_valid_ammo_probability_candidates"));
        return lastPositive;
    }

    /** Legacy helper; production refresh uses {@link #roll} and never magazine-capacity grouping. */
    public static <G, A> Companion<A> resolve(G gun, Function<G, A> loadedAmmoLookup,
            ToIntFunction<G> baseMagazineLookup) {
        Objects.requireNonNull(gun);
        A ammo = loadedAmmoLookup.apply(gun);
        if (ammo == null) throw new IllegalArgumentException(Texts.tr("message.tacz_pubg.loot_gun_ammo_links.gun_has_no_currently_valid_corresponding_ammo_resource"));
        int capacity = baseMagazineLookup.applyAsInt(gun);
        return new Companion<>(ammo, capacity, AmmoGrouping.companionPileCount(capacity), ROUNDS_PER_PILE);
    }
}
