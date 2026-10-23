package com.example.examplemod.lootspawn.client;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure counterpart of TaCZ r7's GROUND visibility rules, independent of mutable render state. */
public final class GroundLootBonePolicy {
    private static final Pattern OPTICAL_MASK = Pattern.compile("(division|ocular|ocular_scope|ocular_sight)(_[0-9]+)?");
    private GroundLootBonePolicy() {}

    public record Appearance(boolean scope, boolean scopeMount, int extendedMagLevel,
            boolean tacticalHandguard, boolean chambered, boolean loaded,
            Set<String> installedTypes, Set<String> adapterNodes, boolean showMuzzle) {
        public Appearance {
            installedTypes = Set.copyOf(installedTypes);
            adapterNodes = Set.copyOf(adapterNodes);
        }
        public static final Appearance EMPTY = new Appearance(false, false, 0, false,
                false, false, Set.of(), Set.of(), false);
    }

    public static boolean gunVisible(String rawName, String rawParent, Appearance appearance) {
        String name = normalize(rawName);
        if (name.equals("lefthand") || name.equals("righthand") || name.equals("lefthand_pos")
                || name.equals("righthand_pos") || name.equals("additional_magazine")
                || name.equals("muzzle_flash") || name.startsWith("laser_beam")) return false;
        if (normalize(rawParent).equals("attachment_adapter")) return appearance.adapterNodes.contains(rawName);
        return switch (name) {
            case "mount" -> appearance.scope && appearance.scopeMount;
            case "carry", "sight" -> !appearance.scope;
            case "sight_folded" -> appearance.scope;
            case "mag_standard" -> appearance.extendedMagLevel == 0;
            case "mag_extended_1" -> appearance.extendedMagLevel == 1;
            case "mag_extended_2" -> appearance.extendedMagLevel == 2;
            case "mag_extended_3" -> appearance.extendedMagLevel == 3;
            case "handguard_default" -> !appearance.tacticalHandguard;
            case "handguard_tactical" -> appearance.tacticalHandguard;
            case "bullet_in_barrel" -> appearance.chambered;
            case "bullet_in_mag", "bullet_chain" -> appearance.loaded;
            case "muzzle_default" -> !appearance.installedTypes.contains("muzzle") || appearance.showMuzzle;
            case "stock_default", "grip_default", "laser_default", "extended_mag_default" ->
                    !appearance.installedTypes.contains(name.substring(0, name.length() - "_default".length()));
            default -> true;
        };
    }

    public static boolean supplementVisible(String rawName) {
        return supplementVisible(null, rawName);
    }

    public static boolean supplementVisible(String assetId, String rawName) {
        String name = normalize(rawName);
        // These are optical masks/reticles or runtime effects, not physical external geometry.
        if (name.startsWith("laser_beam") || name.equals("lefthand_pos") || name.equals("righthand_pos")) return false;
        // These packs author laser rays as emissive cubes rather than TaCZ laser_beam nodes.
        // Match asset AND bone: other packs may use the same name for real illuminated housings.
        if (normalize(assetId).equals("suffuse:sight_dbala2") && name.equals("scope_body_illuminated")) return false;
        if (normalize(assetId).equals("classicr:sight_canted_laser") && name.equals("beam_illuminated")) return false;
        return !(name.startsWith("division") || name.startsWith("ocular")) || !OPTICAL_MASK.matcher(name).matches();
    }

    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
