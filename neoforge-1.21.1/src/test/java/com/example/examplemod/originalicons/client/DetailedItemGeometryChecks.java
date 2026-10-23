package com.example.examplemod.originalicons.client;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Checks model-unit conversion and exact transformed mesh bounds without loading game registries. */
public final class DetailedItemGeometryChecks {
    private static int checks;

    public static void main(String[] args) {
        float[] mesh = {
                0, 0, 0, 100, 200, -10, 20, -30,
                16, 0, 0, 300, 400, -40, 50, -60,
                0, 16, 16, 500, 600, -70, 80, -90
        };
        AABB identity = DetailedItemRenderer.transformedBounds(mesh, new Matrix4f());
        bounds(identity, 0, 0, 0, 1, 1, 1, "positions use model units and ignore UV/normal channels");
        AABB scaled = DetailedItemRenderer.transformedBounds(mesh, new Matrix4f().translate(2, 3, 4).scale(2, 3, 4));
        bounds(scaled, 2, 3, 4, 4, 6, 8, "display transform is applied to model vertices");

        float[] diagonal = {
                0, 0, 0, 0, 0, 0, 1, 0,
                16, 16, 0, 1, 0, 0, 1, 0,
                0, 0, 16, 0, 1, 0, 1, 0
        };
        AABB rotated = DetailedItemRenderer.transformedBounds(diagonal, new Matrix4f().rotateZ((float) Math.PI / 4));
        check(rotated.getXsize() < 1e-6,
                "bounds come from drawable vertices rather than phantom corners of the untransformed box");
        check(Math.abs(rotated.maxY - Math.sqrt(2)) < 1e-6 && Math.abs(rotated.maxZ - 1) < 1e-6,
                "rotated detailed model retains its actual extents");

        // Imported models may have asymmetric origins and unrelated item-frame transforms.
        AABB wearable = new AABB(-.4, -.3, -.45, .6, .7, .35);
        var backpack = WearableItemRenderer.placement(new DetailedItemRenderer.WearableGeometry(wearable, .2), true);
        Matrix4f packTransform = new Matrix4f().translate((float) backpack.x(), (float) backpack.y(), (float) backpack.z())
                .scale(backpack.scale(), -backpack.scale(), -backpack.scale());
        AABB packBounds = transform(wearable, packTransform);
        check(packTransform.determinant() > 0, "backpack placement preserves outward triangle winding for culled baked models");
        check(Math.abs(backpack.z() - .2 * backpack.scale() - .125) < 1e-6 && Math.abs(packBounds.minY - .02) < 1e-6
                && packBounds.minZ < .125, "backpack panel rests on the back while shoulder loops reach toward the chest");
        check(packTransform.transformDirection(new org.joml.Vector3f(0, 0, -1)).z() > 0,
                "source backpack exterior faces the rear of the player, and shoulder straps face the player");
        check(Math.abs(packBounds.minX + packBounds.maxX) < 1e-6 && packBounds.getXsize() <= .640001
                && packBounds.getYsize() <= .740001, "backpack fitting handles an off-center source model");
        var vest = WearableItemRenderer.placement(new DetailedItemRenderer.WearableGeometry(wearable, .2), false);
        Matrix4f vestTransform = new Matrix4f().translate((float) vest.x(), (float) vest.y(), (float) vest.z())
                .scale(vest.scale(), -vest.scale(), -vest.scale());
        AABB vestBounds = transform(wearable, vestTransform);
        check(vestTransform.determinant() > 0 && Math.abs(vestBounds.minY + vestBounds.maxY - .75) < 1e-6
                && Math.abs(vestBounds.minZ + vestBounds.maxZ) < 1e-6,
                "vest fitting preserves outward winding and centers imported geometry on the torso");
        for (double multiplier : new double[]{.1, 5}) {
            var configured = WearableItemRenderer.placement(new DetailedItemRenderer.WearableGeometry(wearable, .2), true, multiplier);
            check(Math.abs(configured.scale() - backpack.scale() * multiplier) < 1e-6,
                    "Wearable multiplier applies after source fitting");
            check(Math.abs(configured.z() - .2 * configured.scale() - .125) < 1e-6,
                    "Resizing keeps the backpack panel attached to the torso");
        }
        float[] paddedPanel = new float[72];
        points(paddedPanel, 0, new float[]{5, 5, 10, 11, 5, 10, 8, 11, 10});
        points(paddedPanel, 24, new float[]{0, 0, 0, 16, 16, 0, 16, 0, 0});
        points(paddedPanel, 48, new float[]{13, 5, 16, 15, 5, 16, 14, 11, 16});
        var contact = DetailedItemRenderer.wearableGeometry(paddedPanel);
        check(Math.abs(contact.backPanelZ() - .125) < 1e-6 && contact.backPanelZ() < contact.bounds().maxZ,
                "shoulder straps do not move the broad padded panel away from the torso");
        System.out.println("Detailed item geometry checks passed: " + checks);
    }

    private static void points(float[] target, int start, float[] xyz) {
        for (int vertex = 0; vertex < 3; vertex++)
            for (int axis = 0; axis < 3; axis++) target[start + vertex * 8 + axis] = xyz[vertex * 3 + axis];
    }

    private static AABB transform(AABB bounds, Matrix4f matrix) {
        float[] corners = new float[8 * 8];
        int at = 0;
        for (double x : new double[]{bounds.minX, bounds.maxX})
            for (double y : new double[]{bounds.minY, bounds.maxY})
                for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                    corners[at] = (float) x * 16; corners[at + 1] = (float) y * 16; corners[at + 2] = (float) z * 16;
                    at += 8;
                }
        return DetailedItemRenderer.transformedBounds(corners, matrix);
    }

    private static void bounds(AABB actual, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, String message) {
        check(Math.abs(actual.minX - minX) < 1e-6 && Math.abs(actual.minY - minY) < 1e-6
                && Math.abs(actual.minZ - minZ) < 1e-6 && Math.abs(actual.maxX - maxX) < 1e-6
                && Math.abs(actual.maxY - maxY) < 1e-6 && Math.abs(actual.maxZ - maxZ) < 1e-6, message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
