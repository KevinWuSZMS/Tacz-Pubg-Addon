package com.example.examplemod.lootspawn.client;

import com.example.examplemod.localization.Texts;

/** Pure geometry: retain an already-flat pose, otherwise turn its thin axis upward. */
public record GroundLootFlatPose(int xRotationDegrees, int zRotationDegrees,
        double offsetX, double offsetY, double offsetZ) {

    public static GroundLootFlatPose fromBounds(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        double[] coordinates = {minX, minY, minZ, maxX, maxY, maxZ};
        for (double coordinate : coordinates) {
            if (!Double.isFinite(coordinate)) throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_flat_pose.non_finite_gun_bounds"));
        }
        double width = maxX - minX, height = maxY - minY, depth = maxZ - minZ;
        if (width < 1e-7 || height < 1e-7 || depth < 1e-7) {
            throw new IllegalArgumentException(Texts.tr("error.tacz_pubg.ground_loot_flat_pose.empty_or_degenerate_gun_bounds"));
        }
        // The tolerance avoids rotating an authored flat model because of tiny mesh differences.
        double thinnest = Math.min(width, Math.min(height, depth));
        if (height <= thinnest * 1.05) {
            return new GroundLootFlatPose(0, 0, -(minX + maxX) / 2, -minY, -(minZ + maxZ) / 2);
        }
        if (width <= depth) {
            // Z +90: (x,y,z) -> (-y,x,z).
            return new GroundLootFlatPose(0, 90, (minY + maxY) / 2, -minX, -(minZ + maxZ) / 2);
        }
        // X +90: (x,y,z) -> (x,-z,y).
        return new GroundLootFlatPose(90, 0, -(minX + maxX) / 2, maxZ, -(minY + maxY) / 2);
    }
}
