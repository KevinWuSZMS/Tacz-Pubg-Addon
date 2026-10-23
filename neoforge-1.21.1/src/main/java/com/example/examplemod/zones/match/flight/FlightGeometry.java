package com.example.examplemod.zones.match.flight;

import net.minecraft.world.phys.Vec3;

/** Four seats per row, with the centre column reserved as an aisle. Seat ids never compact. */
public final class FlightGeometry {
    private FlightGeometry() {}
    public static Vec3 offset(int slot, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length < 1e-9) { dx = 0; dz = 1; }
        else { dx /= length; dz /= length; }
        int column = Math.floorMod(slot, 4);
        double lateral = column < 2 ? column - 2 : column - 1;
        double behind = Math.max(0, slot / 4);
        return new Vec3(dz * lateral - dx * behind, 0, -dx * lateral - dz * behind);
    }
}
