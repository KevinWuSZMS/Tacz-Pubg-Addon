package com.example.examplemod.throwables.client;

import com.example.examplemod.originalicons.OriginalIconCatalog;
import java.util.List;

/** Selection regressions without starting a window, a world or the item renderer. */
public final class RadialWheelChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(RadialWheelLayout.actions(false).equals(List.of("frag_grenade", "sticky_bomb", "stun_grenade",
                "smoke_grenade", "molotov", "blue_zone_grenade")), "throwables follow the requested six-slot order");
        check(RadialWheelLayout.actions(true).equals(List.of("bandage", "first_aid_kit", "med_kit",
                "adrenaline_syringe", "painkiller", "energy_drink")), "medicine follows the requested six-slot order");
        for (boolean medicine : new boolean[]{false, true}) {
            for (String action : RadialWheelLayout.actions(medicine)) {
                var icon = OriginalIconCatalog.find("item", "tacz_pubg:" + action);
                check(icon != null, "forced native icon is mapped: " + action);
                try (var resource = RadialWheelChecks.class.getResourceAsStream("/assets/" + icon.texture().replace(':', '/'))) {
                    check(resource != null, "forced native icon is packaged: " + action);
                }
            }
        }
        for (double radius : new double[]{38, 64, 110}) {
            double center = RadialWheelLayout.innerRadius(radius);
            check(RadialWheelLayout.sector(0, 0, center) == -1, "opening has no selection");
            check(RadialWheelLayout.sector(0, -center, center) == -1, "center edge stays neutral");
            check(RadialWheelLayout.sector(0, -center - .001, center) == 0, "crossing the center selects top");
            for (int slot = 0; slot < 6; slot++) {
                for (double offset : new double[]{-29.999, -15, 0, 15, 29.999}) {
                    double angle = Math.toRadians(-90 + 60 * slot + offset);
                    for (double distance : new double[]{center + .01, radius, radius * 20}) {
                        check(RadialWheelLayout.sector(Math.cos(angle) * distance, Math.sin(angle) * distance, center) == slot,
                                "direction stays selected across the whole sector and beyond the outer rim");
                    }
                }
                double afterBoundary = Math.toRadians(-90 + 60 * slot + 30.001);
                check(RadialWheelLayout.sector(Math.cos(afterBoundary) * radius, Math.sin(afterBoundary) * radius, center)
                        == (slot + 1) % 6, "clockwise boundary enters the next slot, including wraparound");
            }
            var pointer = new RadialWheelLayout.Pointer();
            pointer.move(0, -radius * 100, radius);
            check(pointer.hovered(radius) == 0, "a large upward flick selects top");
            pointer.move(0, radius, radius);
            check(pointer.hovered(radius) == -1, "overshooting does not trap the pointer away from center");
            pointer.move(0, radius, radius);
            check(pointer.hovered(radius) == 3, "pointer can reverse into the bottom slot");
            pointer.move(Double.NaN, 0, radius);
            check(pointer.hovered(radius) == 3, "invalid mouse samples do not corrupt selection");
            pointer.reset();
            check(pointer.hovered(radius) == -1, "reopening or resizing resets selection");
        }
        check(RadialWheelLayout.sector(Double.NaN, 0, 10) == -1, "invalid coordinates never select a slot");
        System.out.println("Radial wheel checks passed: " + checks + "; no game launched.");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
