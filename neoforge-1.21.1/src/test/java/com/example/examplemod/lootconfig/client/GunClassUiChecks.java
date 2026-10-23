package com.example.examplemod.lootconfig.client;

import com.example.examplemod.lootconfig.GunClass;
import com.example.examplemod.lootconfig.GunSelection;
import com.example.examplemod.lootconfig.LootConfigModel.General;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Raw class percentages and root hand-off are exercised with the production form. */
public final class GunClassUiChecks {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejected(Runnable action, String label) {
        try { action.run(); throw new AssertionError("Accepted " + label); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Map<GunClass, Integer> counts = new EnumMap<>(GunClass.class);
        counts.put(GunClass.RIFLE, 6); counts.put(GunClass.SMG, 2); counts.put(GunClass.HEAVY, 2);
        var automatic = new GunClassForm(GunSelection.defaults(), counts);
        check(automatic.parse().isAutomatic(), "opening the form preserves a dynamic default");
        check(automatic.percentages.get("rifle").equals("60") && automatic.percentages.get("smg").equals("20")
                && automatic.percentages.get("heavy").equals("20"), "default percentages use gun type counts");
        check(automatic.percentages.keySet().equals(GunClass.IDS), "all nine families remain independently editable");
        check(automatic.equalsValues(GunSelection.defaults(), Map.of(GunClass.RIFLE, 100)), "catalog growth preserves automatic source semantics");
        var form = new GunClassForm(GunSelection.defaults(), counts);
        form.automatic = false; form.percentages.put("rifle", "25"); form.percentages.put("smg", "50"); form.percentages.put("heavy", "25");
        GunSelection custom = form.parse();
        check(custom.validate().isEmpty(), "a complete custom 100 percent distribution is valid");
        check(custom.configuredProbabilities(counts).get(GunClass.RIFLE) == .25, "percent text converts to stored probability");
        check(form.equalsValues(custom, counts), "acknowledged custom values match the actual raw form");
        check(form.parse().equals(custom), "custom probabilities remain stable through catalog count changes");
        List<String> baseline = form.values();
        form.percentages.put("rifle", "20");
        check(form.parse().validate().stream().anyMatch(GunSelection::isTotalError), "temporary 95 percent drafts parse, while final validation requests 100 percent");
        check(!form.values().equals(baseline), "raw tracking includes unbalanced percentages for delayed ACK protection");
        for (String invalid : List.of("", "-", "NaN", "Infinity", "100.1", "-1")) {
            form.percentages.put("rifle", invalid); rejected(form::parse, "incomplete or invalid class value " + invalid);
            check(form.percentages.get("rifle").equals(invalid), "invalid raw text remains available for correction and tab navigation");
        }
        form.percentages.put("rifle", "25");
        var restored = new GunClassForm(GunSelection.defaults(), Map.of(GunClass.RIFLE, 1, GunClass.PISTOL, 3));
        check(restored.percentages.get("rifle").equals("25") && restored.percentages.get("pistol").equals("75")
                && restored.parse().isAutomatic(), "restore recomputes counts and keeps the dynamic source");

        // Actual snapshot counts: copied prototype catalog (424) and full loaded native catalog (431).
        checkEditableCountDefault(Map.of(GunClass.RIFLE, 140, GunClass.SMG, 59, GunClass.MG, 34,
                GunClass.SHOTGUN, 30, GunClass.SNIPER, 51, GunClass.DMR, 30, GunClass.PISTOL, 67,
                GunClass.HEAVY, 13, GunClass.OTHER, 0), 424);
        checkEditableCountDefault(Map.of(GunClass.RIFLE, 141, GunClass.SMG, 59, GunClass.MG, 34,
                GunClass.SHOTGUN, 31, GunClass.SNIPER, 52, GunClass.DMR, 30, GunClass.PISTOL, 71,
                GunClass.HEAVY, 13, GunClass.OTHER, 0), 431);

        Class<?> type = Class.forName("com.example.examplemod.lootconfig.client.LootConfigScreen$GeneralForm");
        var constructor = type.getDeclaredConstructor(General.class); constructor.setAccessible(true);
        var field = type.getDeclaredField("gunSelection"); field.setAccessible(true);
        var parse = type.getDeclaredMethod("parse"); parse.setAccessible(true);
        Object root = constructor.newInstance(General.defaults()); field.set(root, custom);
        General parsed = (General) parse.invoke(root);
        check(parsed.gunSelection().equals(custom), "the root's twelve-field model preserves a custom class distribution");
        var equals = type.getDeclaredMethod("equalsValues", General.class); equals.setAccessible(true);
        check(!(Boolean) equals.invoke(root, General.defaults()), "root dirty tracking includes class selection");
        check((Boolean) equals.invoke(root, parsed), "matching ACK values clear the class selection dirty state");
        System.out.println("GunClassUiChecks: " + checks + " dynamic-default, raw-draft, total and root hand-off checks passed; no game launched.");
    }

    private static void checkEditableCountDefault(Map<GunClass, Integer> counts, int expectedGunTypes) {
        check(counts.values().stream().mapToInt(Integer::intValue).sum() == expectedGunTypes, "actual catalog fixture total");
        var form = new GunClassForm(GunSelection.defaults(), counts);
        // The real responder selects manual mode after editing a displayed field; keep its original text.
        form.automatic = false;
        GunSelection manual = form.parse();
        check(manual.validate().isEmpty(), expectedGunTypes + " gun defaults remain saveable after switching their displayed values to manual mode");
        check(form.equalsValues(manual, counts), expectedGunTypes + " gun percentages round-trip through the acknowledged model");
    }
}
