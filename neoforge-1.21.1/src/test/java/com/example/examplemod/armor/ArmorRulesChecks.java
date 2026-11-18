package com.example.examplemod.armor;

public final class ArmorRulesChecks {
    private static int checks;
    public static void main(String[] args) {
        check(ArmorRules.maximum(true, 1) == 80 && ArmorRules.maximum(true, 3) == 230, "helmet health");
        check(ArmorRules.maximum(false, 1) == 200 && ArmorRules.maximum(false, 3) == 250, "vest health");
        check(Math.abs(ArmorRules.multiplier(3, true, false) - .45f) < .0001, "level 3 protection");
        for (int tier = 1; tier <= 3; tier++) for (boolean helmet : new boolean[]{true, false}) {
            float brokenFactor = helmet ? 1 : .8f;
            check(ArmorRules.multiplier(tier, helmet, true) == brokenFactor,
                    "Official 7.2: broken helmets grant no protection; every vest tier retains twenty percent");
            check(ArmorRules.wear(8, brokenFactor) == (helmet ? 0 : 800),
                    "Broken-vest protection applies the same twenty-percent absorption to later hits");
            int maximum = ArmorRules.maximum(helmet, tier) * ArmorRules.PRECISION;
            var partial = ArmorRules.afterWear(helmet, maximum, maximum - 2, 1);
            check(partial.damage() == maximum - 1 && !partial.removeItem(), "The last durability point still keeps either piece equipped");
            float lastHitFactor = ArmorRules.multiplier(tier, helmet, false);
            var exhausted = ArmorRules.afterWear(helmet, maximum, partial.damage(), ArmorRules.wear(8, lastHitFactor));
            check(exhausted.damage() == maximum && exhausted.removeItem() == helmet,
                    "The last accepted hit removes helmets but retains exhausted vests");
            check(lastHitFactor < brokenFactor && ArmorRules.multiplier(tier, helmet, exhausted.damage() >= maximum) == brokenFactor,
                    "The exhaustion hit uses pre-hit tier protection; the next hit uses the official broken-piece rule");
            var overkill = ArmorRules.afterWear(helmet, maximum, maximum - 1, Integer.MAX_VALUE);
            check(overkill.damage() == maximum && overkill.removeItem() == helmet,
                    "Very large damage cannot overflow durability or delete the retained vest");
            var nextHit = ArmorRules.afterWear(helmet, maximum, exhausted.damage(), ArmorRules.wear(8, brokenFactor));
            check(nextHit.damage() == maximum && nextHit.removeItem() == helmet,
                    "Repeated protected hits leave the vest equipped at zero durability");
        }
        // A 40-PUBG-HP bullet is 8 Minecraft HP; a level 3 vest absorbs 22 health.
        check(ArmorRules.wear(8, .45f) == 2200, "armor wear uses absorbed PUBG health");
        int single = ArmorRules.wear(8, .45f);
        int split = ArmorRules.wear(8 * .3f, .45f) + ArmorRules.wear(8 * .7f, .45f);
        check(Math.abs(single - split) <= 1, "TaCZ AP split does not double durability loss");
        check(ArmorRules.wear(Float.NaN, .45f) == 0 && ArmorRules.wear(-1, .45f) == 0, "invalid damage cannot repair or destroy armor");
        check(ArmorRules.remaining(8000, 9000) == 0, "overkill clamps armor health");
        check(ArmorRules.afterWear(false, 0, 0, 800).equals(new ArmorRules.WearResult(0, false)),
                "A command-modified vest without max durability cannot crash damage processing");
        check(ArmorRules.afterWear(true, 0, 0, 800).equals(new ArmorRules.WearResult(0, true)),
                "A command-modified helmet preserves safe depletion behavior");
        System.out.println("Armor rules checks passed: " + checks + "; no game launched.");
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
}
