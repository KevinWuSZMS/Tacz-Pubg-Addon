package com.example.examplemod.originalicons.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Fits the selected detailed or baked geometry to a torso, independently of item-frame transforms. */
public final class WearableItemRenderer {
    private static final Map<BakedModel, DetailedItemRenderer.WearableGeometry> BAKED_BOUNDS = new IdentityHashMap<>();
    private WearableItemRenderer() {}

    public static void clear() { BAKED_BOUNDS.clear(); }

    public static void render(ItemStack stack, boolean backpack, AbstractClientPlayer player, PoseStack poses,
            MultiBufferSource buffers, int light) {
        var renderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = renderer.getModel(stack, player.level(), player, player.getId());
        var geometry = DetailedItemRenderer.wearableGeometry(stack)
                .orElseGet(() -> BAKED_BOUNDS.computeIfAbsent(model, WearableItemRenderer::bakedGeometry));
        Placement placement = placement(geometry, backpack,
                com.example.examplemod.modelscale.ModelScaleService.worn(stack));
        poses.pushPose();
        try {
            poses.translate(placement.x, placement.y, placement.z);
            float scale = placement.scale;
            // Source +Z is the vest front and backpack shoulder side; humanoid +Z is behind the player.
            // Two negative axes preserve outward face winding for the culled baked models.
            poses.scale(scale, -scale, -scale);
            renderer.render(stack, ItemDisplayContext.NONE, false, poses, buffers, light,
                    OverlayTexture.NO_OVERLAY, model);
        } finally { poses.popPose(); }
    }

    static Placement placement(DetailedItemRenderer.WearableGeometry geometry, boolean backpack) {
        return placement(geometry, backpack, 1);
    }
    static Placement placement(DetailedItemRenderer.WearableGeometry geometry, boolean backpack, double multiplier) {
        AABB bounds = geometry.bounds();
        float scale = (float) Math.min((backpack ? .74 : .78) / Math.max(.001, bounds.getYsize()),
                (backpack ? .64 : .68) / Math.max(.001, bounds.getXsize()));
        scale *= (float) multiplier;
        double centerX = (bounds.minX + bounds.maxX) * .5;
        double centerZ = (bounds.minZ + bounds.maxZ) * .5;
        if (backpack) {
            // Put the padded bag panel against the back; let the shoulder loops wrap toward the chest.
            return new Placement(scale, -centerX * scale, .02 + bounds.maxY * scale, .125 + geometry.backPanelZ() * scale);
        }
        return new Placement(scale, -centerX * scale,
                .375 + (bounds.minY + bounds.maxY) * .5 * scale, centerZ * scale);
    }

    private static DetailedItemRenderer.WearableGeometry bakedGeometry(BakedModel model) {
        var triangles = new ArrayList<float[]>();
        for (int face = -1; face < 6; face++) {
            var quads = model.getQuads(null, face < 0 ? null : Direction.values()[face],
                    RandomSource.create(42), ModelData.EMPTY, null);
            for (var quad : quads) {
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                for (int[] corners : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                    float[] triangle = new float[24];
                    for (int vertex = 0; vertex < 3; vertex++) {
                        int at = corners[vertex] * stride;
                        for (int axis = 0; axis < 3; axis++)
                            triangle[vertex * 8 + axis] = Float.intBitsToFloat(vertices[at + axis]) * 16;
                    }
                    triangles.add(triangle);
                }
            }
        }
        if (triangles.isEmpty()) return new DetailedItemRenderer.WearableGeometry(
                new AABB(-.25, -.375, -.125, .25, .375, .125), .125);
        float[] data = new float[triangles.size() * 24];
        for (int at = 0; at < triangles.size(); at++) System.arraycopy(triangles.get(at), 0, data, at * 24, 24);
        return DetailedItemRenderer.wearableGeometry(data);
    }

    record Placement(float scale, double x, double y, double z) {}
}
