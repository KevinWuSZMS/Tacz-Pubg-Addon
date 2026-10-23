package com.example.examplemod.lootconfig.client;

import com.example.examplemod.lootconfig.LootConfigModel;
import com.example.examplemod.lootconfig.LootConfigModel.AmmoOption;
import com.example.examplemod.lootconfig.LootConfigModel.Entry;
import com.example.examplemod.lootconfig.LootConfigModel.GunAmmoSupply;
import com.example.examplemod.lootconfig.client.GunAmmoSupplyScreen.Draft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Exercises actual editor drafts and layout helpers without constructing a Screen or starting Minecraft. */
public final class GunAmmoSupplyUiChecks {
    private static int checks;
    private static final Set<String> AMMO = Set.of("test:rifle", "test:pistol");

    public static void main(String[] args) throws Exception {
        GunAmmoSupply rifle = GunAmmoSupply.defaults(30, true);
        Draft draft = new Draft(rifle, true);
        check(draft.parse(AMMO).equals(rifle), "server rifle defaults survive the editor without client classification");
        check(draft.dynamicDefaults, "null configuration starts in dynamic-default mode");
        check(draft.probabilities[0].equals("30") && draft.probabilities[1].equals("60")
                && draft.probabilities[2].equals("10"), "probability fields display percentages rather than fractions");
        check(draft.groups[0].equals("1") && draft.groups[1].equals("2") && draft.groups[2].equals("3"),
                "three plans carry their own editable group counts");
        check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalProbabilityText()).equals("100"), "default total avoids binary-floating-point display noise");
        check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalRoundsText(0)).equals("30") && draft.totalRoundsText(1).equals("60")
                && draft.totalRoundsText(2).equals("90"), "plan total uses the plan group count and the gun's rounds per group");
        GunAmmoSupply tiny = new GunAmmoSupply(List.of(new AmmoOption("self", 1, .6),
                new AmmoOption("self", 2, .4), new AmmoOption("self", 3, Double.MIN_VALUE)), 30);
        Draft tinyDraft = new Draft(tiny, false);
        check(tinyDraft.probabilities[2].length() <= 24 && tinyDraft.parse(AMMO).equals(tiny),
                "a valid tiny probability fits the actual text field and round-trips without truncation");

        GunAmmoSupply other = GunAmmoSupply.defaults(15, false);
        draft.replace(other, true);
        check(draft.probabilities[0].equals("60") && draft.probabilities[1].equals("30")
                && draft.probabilities[2].equals("10"), "other family defaults are copied from the server");
        check(draft.parse(AMMO).equals(other), "restoring another gun's defaults does not retain rifle probabilities");

        draft.ammoIds[0] = "test:rifle";
        draft.ammoIds[1] = "test:pistol";
        draft.groups[0] = "4";
        draft.rounds = "45";
        draft.probabilities[0] = "20";
        draft.probabilities[1] = "30";
        draft.probabilities[2] = "50";
        draft.dynamicDefaults = false;
        GunAmmoSupply custom = draft.parse(AMMO);
        check(custom.equals(new GunAmmoSupply(List.of(new AmmoOption("test:rifle", 4, .2),
                new AmmoOption("test:pistol", 2, .3), new AmmoOption("self", 3, .5)), 45)),
                "one probability is attached to each complete ammo-and-groups plan");
        check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalRoundsText(0)).equals("180"), "a custom plan's total is shown without a second group draw");
        check(!draft.dynamicDefaults, "custom plans retain explicit configuration mode");

        List<String> savedRaw = draft.values();
        draft.rounds = "";
        List<String> unfinished = draft.values();
        Draft copied = draft.copy();
        check(copied.values().equals(unfinished), "narrow-screen child editor receives an exact raw copy, including incomplete input");
        copied.groups[0] = "9";
        copied.rounds = "99";
        check(draft.values().equals(unfinished), "cancelling the child cannot mutate its parent's retained raw draft");
        check(!unfinished.equals(savedRaw), "unfinished input remains distinct from the previously valid draft");
        check(rejected(draft), "an empty field cannot be committed");
        check(unfinished.equals(draft.values()), "failed validation leaves all raw text intact");
        check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalRoundsText(0)).equals("待填"), "the quantity preview handles an incomplete field");
        draft.rounds = "45";
        check(savedRaw.equals(draft.values()), "restoring input reconstructs the same draft across child navigation");

        for (String invalid : new String[]{"NaN", "Infinity", "-Infinity", "101", "-1", "", "1e999999999"}) {
            draft.probabilities[0] = invalid;
            List<String> before = draft.values();
            check(rejected(draft), "invalid percentage cannot be committed: " + invalid);
            check(before.equals(draft.values()), "percentage validation does not alter raw input");
            check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalProbabilityText()).equals("待填写"), "invalid numeric display remains bounded");
        }
        draft.probabilities[0] = "21";
        check(rejected(draft), "a finite total of 101 percent cannot be committed");
        draft.probabilities[0] = "1e-999999999";
        check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalProbabilityText()).equals("80"), "extreme underflow input cannot allocate an unbounded decimal display");
        draft.probabilities[0] = "20";

        draft.ammoIds[0] = "missing:ammo";
        check(rejected(draft), "only self or an actually loaded ammo choice is accepted");
        draft.ammoIds[0] = "self";
        draft.ammoIds[1] = "self";
        check(draft.parse(Set.of()).ammoOptions().stream().allMatch(option -> option.ammoId().equals("self")),
                "self plans do not require a concrete ammo ID in the picker");

        for (String invalid : new String[]{"0", "65", "1.5", "2147483648", "NaN", ""}) {
            draft.groups[0] = invalid;
            check(rejected(draft), "invalid group count cannot be committed: " + invalid);
            check(draft.groups[0].equals(invalid), "group validation preserves invalid text");
        }
        draft.groups[0] = "64";
        for (String invalid : new String[]{"0", "4097", "1.5", "2147483648", "NaN", ""}) {
            draft.rounds = invalid;
            check(rejected(draft), "invalid rounds per group cannot be committed: " + invalid);
            check(draft.rounds.equals(invalid), "round validation preserves invalid text");
        }
        draft.rounds = "4096";
        check(draft.parse(AMMO).ammoOptions().getFirst().groups() == 64, "maximum group count remains editable");
        check(com.example.examplemod.localization.TestLanguages.chinese(draft.totalRoundsText(0)).equals("262144"), "the maximum total is exact and does not overflow");

        draft.replace(rifle, true);
        check(draft.parse(AMMO).equals(rifle) && draft.dynamicDefaults, "reset restores the complete dynamic-default plan");
        draft.probabilities[0] = "100";
        draft.probabilities[1] = draft.probabilities[2] = "0";
        check(draft.parse(AMMO).ammoOptions().getFirst().probability() == 1,
                "zero-probability alternatives and one certain plan are valid");
        for (int index = 0; index < 3; index++) draft.probabilities[index] = "33.3333333333333";
        check(LootConfigModel.validateGunAmmoSupply(draft.parse(AMMO)).isEmpty(), "normal decimal rounding uses the shared sum tolerance");

        // Exercise the parent's actual item form: ordinary weight/count edits must retain the sixth field.
        Entry entry = new Entry("gun", "test:gun", 1, 1, 4, custom);
        Class<?> itemForm = Class.forName("com.example.examplemod.lootconfig.client.LootConfigScreen$ItemForm");
        Constructor<?> constructor = itemForm.getDeclaredConstructor(Entry.class);
        constructor.setAccessible(true);
        Object raw = constructor.newInstance(entry);
        Field weight = itemForm.getDeclaredField("weight");
        weight.setAccessible(true);
        weight.set(raw, "2.5");
        Method parse = itemForm.getDeclaredMethod("parse");
        parse.setAccessible(true);
        Entry weighted = (Entry) parse.invoke(raw);
        check(weighted.weight() == 2.5 && weighted.ammoSupply().equals(custom),
                "parent item edits preserve the gun's explicit ammo plans");
        Object defaultRaw = constructor.newInstance(new Entry("gun", "test:gun", 1, 1, 4));
        check(((Entry) parse.invoke(defaultRaw)).ammoSupply() == null, "parent edits preserve null as dynamic defaults");

        for (int height : new int[]{180, 190, 199, 200, 210, 219, 220, 230, 239, 240, 270, 360, 720}) {
            int count = GunAmmoSupplyScreen.rowsPerPageFor(height);
            int bottom = GunAmmoSupplyScreen.rowsTopFor(height) + (count - 1) * 25 + 20;
            check(count >= 1 && count <= 3, "all plans remain reachable through bounded pages");
            if (count < 3) {
                check(bottom <= height - 94, "numeric inputs clear the page controls even at low height");
                check(height - 90 + 20 <= height - 69, "page controls clear the quantity summary");
            } else check(bottom <= height - 76, "all three visible rows clear the quantity summary");
            if (height >= 240) check(count == 3, "normal Minecraft GUI heights show all three complete plans");
        }
        System.out.println("GunAmmoSupplyUiChecks: " + checks
                + " single-draw plan, raw-input, quantity, parent-form and responsive layout checks passed; no game launched.");
    }

    private static boolean rejected(Draft draft) {
        try { draft.parse(AMMO); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
