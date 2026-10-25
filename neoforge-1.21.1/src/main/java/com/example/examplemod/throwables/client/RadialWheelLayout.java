package com.example.examplemod.throwables.client;

import java.util.List;

/** Shared slot order and clockwise hit geometry; independent of screen position and item models. */
public final class RadialWheelLayout {
    public static final int SLOT_COUNT = 6;
    private static final double TURN = Math.PI * 2;
    private static final List<String> THROWABLES = List.of(
            "frag_grenade", "sticky_bomb", "stun_grenade", "smoke_grenade", "molotov", "blue_zone_grenade");
    private static final List<String> MEDICINE = List.of(
            "bandage", "first_aid_kit", "med_kit", "adrenaline_syringe", "painkiller", "energy_drink");

    private RadialWheelLayout() {}
    public static List<String> actions(boolean medicine) { return medicine ? MEDICINE : THROWABLES; }
    public static double outerRadius(int width, int height) { return Math.min(110, Math.min(width, height) * .24); }
    public static double innerRadius(double outer) { return outer * .38; }
    public static double slotAngle(int slot) { return -Math.PI / 2 + slot * TURN / SLOT_COUNT; }

    public static int sector(double x, double y, double deadZone) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.hypot(x, y) <= deadZone) return -1;
        double angle = (Math.atan2(y, x) + Math.PI / 2 + Math.PI / SLOT_COUNT + TURN) % TURN;
        return (int) (angle / (TURN / SLOT_COUNT)) % SLOT_COUNT;
    }

    /** A hidden virtual pointer stays near the ring, so overshooting never traps the selection. */
    public static final class Pointer {
        private double x, y;
        public void reset() { x = y = 0; }
        public void move(double dx, double dy, double outerRadius) {
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !(outerRadius > 0)) return;
            x += dx;
            y += dy;
            double length = Math.hypot(x, y);
            if (length > outerRadius) {
                x *= outerRadius / length;
                y *= outerRadius / length;
            }
        }
        public int hovered(double outerRadius) { return sector(x, y, innerRadius(outerRadius)); }
    }
}
