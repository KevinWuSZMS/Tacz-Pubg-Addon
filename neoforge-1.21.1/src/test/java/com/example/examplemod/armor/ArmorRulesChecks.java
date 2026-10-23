package com.example.examplemod.armor;

public final class ArmorRulesChecks {
    public static void main(String[] args) {
        check(ArmorRules.maximum(true, 1) == 80 && ArmorRules.maximum(true, 3) == 230, "helmet health");
        check(ArmorRules.maximum(false, 1) == 200 && ArmorRules.maximum(false, 3) == 250, "vest health");
        check(Math.abs(ArmorRules.multiplier(3, true, false) - .45f) < .0001, "level 3 protection");
        check(ArmorRules.multiplier(3, true, true) == 1, "broken helmet has no protection");
        check(Math.abs(ArmorRules.multiplier(1, false, true) - .8f) < .0001, "broken level 1 vest remains useful");
        check(ArmorRules.multiplier(1, false, true) == ArmorRules.multiplier(3, false, true), "broken vest protection independent of tier");
        // A 40-PUBG-HP bullet is 8 Minecraft HP; a level 3 vest absorbs 22 health.
        check(ArmorRules.wear(8, .45f) == 2200, "armor wear uses absorbed PUBG health");
        int single = ArmorRules.wear(8, .45f);
        int split = ArmorRules.wear(8 * .3f, .45f) + ArmorRules.wear(8 * .7f, .45f);
        check(Math.abs(single - split) <= 1, "TaCZ AP split does not double durability loss");
        check(ArmorRules.wear(Float.NaN, .45f) == 0 && ArmorRules.wear(-1, .45f) == 0, "invalid damage cannot repair or destroy armor");
        check(ArmorRules.remaining(8000, 9000) == 0, "overkill clamps armor health");
        System.out.println("Armor rules checks passed.");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
