package com.example.examplemod.lootconfig.client;

import com.example.examplemod.lootconfig.LootConfigModel.General;
import com.example.examplemod.zones.MapVisualStyle;
import com.example.examplemod.zones.ZoneSettings;
import java.util.List;
import java.util.Set;

/** Exercises the actual child draft and root hand-off without launching a Minecraft client. */
public final class ZoneSettingsUiChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void rejected(Runnable action, String label) {
        try { action.run(); throw new AssertionError("Accepted " + label); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    public static void main(String[] args) throws Exception {
        var original = ZoneSettings.defaults();
        Set<String> allowed = Set.of(original.bombing().ammoId(), "test:rocket");
        var draft = new ZoneSettingsDraft(original);
        check(draft.parse(allowed).equals(original), "opening and applying preserves all defaults");
        List<String> baseline = draft.values();
        draft.damage = "-";
        rejected(() -> draft.parse(allowed), "unfinished damage");
        check(draft.damage.equals("-"), "validation retains an incomplete raw value");
        check(original.equals(ZoneSettings.defaults()), "child edits leave the immutable root source unchanged");
        check(!draft.values().equals(baseline), "cancel detection includes incomplete values");
        draft.damage = "10000"; draft.radius = "0.00001"; draft.edgePercent = "100";
        draft.destroyBlocks = true; draft.ammoId = "test:rocket";
        var changed = draft.parse(allowed);
        check(changed.bombing().ammoId().equals("test:rocket") && changed.bombing().destroyBlocks(),
                "ammunition and block mode round-trip together");
        check(changed.bombing().damage() == 10000 && changed.bombing().damageRadius() == .00001
                && changed.bombing().edgeDamageMultiplier() == 1, "client accepts server endpoint ranges and positive fractional radii");
        for (String invalid : List.of("NaN", "Infinity", "-1", "10000.1", "")) {
            draft.damage = invalid; rejected(() -> draft.parse(allowed), "invalid damage " + invalid);
            check(draft.damage.equals(invalid), "bad numeric authoring remains available for correction");
        }
        draft.damage = "10";
        for (String invalid : List.of("0", "-1", "128.01", "NaN")) {
            draft.radius = invalid; rejected(() -> draft.parse(allowed), "invalid radius " + invalid);
        }
        draft.radius = "128"; draft.edgePercent = "0";
        check(draft.parse(allowed).bombing().damageRadius() == 128, "maximum radius is accepted");
        for (String invalid : List.of("-1", "100.01", "NaN")) {
            draft.edgePercent = invalid; rejected(() -> draft.parse(allowed), "invalid edge multiplier " + invalid);
        }
        draft.edgePercent = "50";
        check(draft.parse(allowed).bombing().edgeDamageMultiplier() == .5, "UI percent converts to the stored multiplier");
        draft.ammoId = "missing:old_rocket";
        rejected(() -> draft.parse(allowed), "an unavailable legacy projectile");
        check(draft.ammoId.equals("missing:old_rocket"), "an unavailable ID remains visible instead of silently selecting a fallback");
        draft.ammoId = original.bombing().ammoId();
        rejected(() -> draft.parse(Set.of()), "an empty server projectile catalog");
        draft.useGlobalVisualStyle = true; draft.colors[1] = "#AbCDef"; draft.opacityPercent[0] = "0";
        draft.opacityPercent[1] = "100";
        var styled = draft.parse(allowed);
        check(styled.useGlobalVisualStyle() && styled.visualStyle().blueZoneRgb() == 0xABCDEF,
                "explicit unified mode and edited hex color reach the parent together");
        check(styled.visualStyle().blueMapOpacity() == 0 && styled.visualStyle().bombingMapOpacity() == 1,
                "both transparency endpoints survive application");
        draft.colors[2] = "EF30";
        rejected(() -> draft.parse(allowed), "an unfinished hex color");
        check(draft.colors[2].equals("EF30"), "unfinished hex text survives validation");
        draft.setStyle(MapVisualStyle.DEFAULT);
        check(draft.useGlobalVisualStyle, "resetting colors preserves the user's explicit presentation mode");
        for (String invalid : List.of("-1", "100.001", "NaN", "")) {
            draft.opacityPercent[2] = invalid; rejected(() -> draft.parse(allowed), "invalid opacity " + invalid);
        }
        draft.setStyle(MapVisualStyle.DEFAULT);
        var handedOff = draft.parse(allowed);

        check(com.example.examplemod.localization.TestLanguages.chinese(draft.spawnHeight).equals("世界限高") && handedOff.bombing().spawnHeight() == Integer.MAX_VALUE,
                "world-height mode preserves the explicit dynamic sentinel");
        for (String value : List.of("-2032", "2031", "0", " 世界限高 ")) {
            draft.spawnHeight = value;
            check(draft.parse(allowed).bombing().spawnHeight() == (value.trim().equals("世界限高") ? Integer.MAX_VALUE : Integer.parseInt(value)),
                    "height editor accepts the complete backend integer range and world mode");
        }
        for (String invalid : List.of("-", "", "2032", "-2033", "12.5", "NaN")) {
            draft.spawnHeight = invalid; rejected(() -> draft.parse(allowed), "invalid generation height " + invalid);
            check(com.example.examplemod.localization.TestLanguages.chinese(draft.spawnHeight).equals(invalid), "height validation preserves its raw input");
        }
        draft.spawnHeight = "世界限高";
        Set<String> blocks = Set.of("minecraft:glass", "minecraft:leaves", "minecraft:air");
        draft.addBlock("minecraft:glass", blocks); draft.addBlock("minecraft:leaves", blocks);
        check(draft.parse(allowed, blocks).bombing().passThroughBlocks().equals(List.of("minecraft:glass", "minecraft:leaves")),
                "registered block IDs survive application in order");
        rejected(() -> draft.addBlock("missing:block", blocks), "an unknown client block registry ID");
        rejected(() -> draft.addBlock("minecraft:glass", blocks), "a duplicate pass-through entry");
        check(draft.passThroughBlocks.size() == 2, "rejected list edits preserve the previous entries");
        draft.blockInput = "minecraft:";
        rejected(() -> draft.parse(allowed, blocks), "a pending block ID awaiting Add");
        check(draft.blockInput.equals("minecraft:"), "a pending block ID remains in the independent raw draft");
        draft.blockInput = "";
        draft.heavyWeaponTerrain.put("test:launcher", true);
        var extended = draft.parse(allowed, blocks);
        check(extended.heavyWeaponTerrain().get("test:launcher") && extended.bombing().passThroughBlocks().size() == 2,
                "heavy terrain overrides and projectile block policy round-trip together");
        draft.setStyle(MapVisualStyle.DEFAULT);
        check(draft.parse(allowed, blocks).heavyWeaponTerrain().equals(extended.heavyWeaponTerrain()),
                "color reset retains independently authored heavy terrain overrides");
        draft.heavyWeaponTerrain.remove("test:launcher");
        check(draft.parse(allowed, blocks).heavyWeaponTerrain().isEmpty(), "restoring a gun's native mode removes its explicit override");
        handedOff = draft.parse(allowed, blocks);

        Class<?> formClass = Class.forName("com.example.examplemod.lootconfig.client.LootConfigScreen$GeneralForm");
        var constructor = formClass.getDeclaredConstructor(General.class); constructor.setAccessible(true);
        var zoneField = formClass.getDeclaredField("zoneSettings"); zoneField.setAccessible(true);
        var parse = formClass.getDeclaredMethod("parse"); parse.setAccessible(true);
        Object form = constructor.newInstance(General.defaults()); zoneField.set(form, handedOff);
        General parsed = (General) parse.invoke(form);
        check(parsed.zoneSettings().equals(handedOff), "root general parsing carries the independently applied child settings into its twelve-field model");
        var equalsValues = formClass.getDeclaredMethod("equalsValues", General.class); equalsValues.setAccessible(true);
        check(!(Boolean) equalsValues.invoke(form, General.defaults()), "root dirty tracking includes zone settings");
        check((Boolean) equalsValues.invoke(form, parsed), "a matching acknowledged model can clear the settings dirty state");
        var expanded = new ZoneSettingsDraft(original);
        expanded.colors[6] = "123456"; expanded.colors[10] = "ABCDEF"; expanded.lootOverlapBlackLayers = "7";
        var expandedStyle = expanded.parse(allowed).visualStyle();
        check(expandedStyle.lootTierRgb().get("very_low") == 0x123456 && expandedStyle.lootTierRgb().get("very_high") == 0xABCDEF
                && expandedStyle.lootOverlapBlackLayers() == 7, "Five-tier palette and overlap n survive child parsing");
        check(new ZoneSettingsDraft(expanded.parse(allowed)).parse(allowed).equals(expanded.parse(allowed)), "Reopening preserves custom palette and n");
        expanded.lootOverlapBlackLayers = "1"; rejected(() -> expanded.parse(allowed), "n below two");
        expanded.lootOverlapBlackLayers = "7";
        expanded.curve("tacz:m320", 8, 1.35, 1.65)[0] = "0.000001";
        check(expanded.parse(allowed).heavyWeaponDamageCurves().get("tacz:m320").damageRadius() == .000001, "Positive fractional heavy radius uses server range");
        expanded.heavyWeaponDamageCurves.get("tacz:m320")[1] = "2";
        expanded.heavyWeaponDamageCurves.get("tacz:m320")[2] = "1";
        check(expanded.parse(allowed).heavyWeaponDamageCurves().get("tacz:m320").p() == 2, "Shape slider endpoints survive parsing");
        expanded.heavyWeaponDamageCurves.get("tacz:m320")[0] = "-";
        rejected(() -> expanded.parse(allowed), "unfinished heavy radius");
        check(expanded.heavyWeaponDamageCurves.get("tacz:m320")[0].equals("-"), "Invalid heavy radius remains in draft");
        System.out.println("ZoneSettingsUiChecks: " + checks + " raw-draft, projectile, color and parent hand-off checks passed; no game launched.");
    }
}
