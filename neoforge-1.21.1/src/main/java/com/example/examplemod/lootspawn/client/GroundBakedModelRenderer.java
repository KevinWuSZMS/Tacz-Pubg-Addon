package com.example.examplemod.lootspawn.client;

import com.example.examplemod.originalicons.client.DetailedItemRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;

/** Baked and detailed item meshes share one placement for drawing and aiming at the visible geometry. */
final class GroundBakedModelRenderer {
    private static final Map<BakedModel, Placement> CACHE = new IdentityHashMap<>();
    private static final Map<AABB, Placement> DETAILED_CACHE = new HashMap<>();
    private GroundBakedModelRenderer() {}
    static void clearCache() { CACHE.clear(); DETAILED_CACHE.clear(); }
    static AABB bounds(ItemStack stack, BakedModel baked) { return placement(stack, baked).bounds; }

    static void render(ItemStack stack, BakedModel baked, ItemRenderer renderer, PoseStack poses,
            MultiBufferSource buffers, int light) {
        Placement placement = placement(stack, baked);
        poses.pushPose();
        var flat = placement.flat;
        poses.translate(flat.offsetX(), flat.offsetY(), flat.offsetZ());
        if (flat.xRotationDegrees() != 0) poses.mulPose(Axis.XP.rotationDegrees(flat.xRotationDegrees()));
        if (flat.zRotationDegrees() != 0) poses.mulPose(Axis.ZP.rotationDegrees(flat.zRotationDegrees()));
        renderer.render(stack, ItemDisplayContext.GROUND, false, poses, buffers, light, OverlayTexture.NO_OVERLAY, baked);
        poses.popPose();
    }

    private static Placement placement(ItemStack stack, BakedModel baked) {
        var detailed = DetailedItemRenderer.groundBounds(stack, baked);
        if (detailed.isPresent()) return DETAILED_CACHE.computeIfAbsent(detailed.get(), GroundBakedModelRenderer::placement);
        return CACHE.computeIfAbsent(baked, model -> {
            PoseStack poses = new PoseStack();
            ClientHooks.handleCameraTransforms(poses, baked, ItemDisplayContext.GROUND, false);
            poses.translate(-.5, -.5, -.5);
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int face = -1; face < 6; face++) {
                var quads = model.getQuads(null, face == -1 ? null : Direction.values()[face], RandomSource.create(42), ModelData.EMPTY, null);
                for (var quad : quads) {
                    int[] vertices = quad.getVertices();
                    int stride = vertices.length / 4;
                    for (int corner = 0; corner < 4; corner++) {
                        int at = corner * stride;
                        Vector3f p = poses.last().pose().transformPosition(new Vector3f(Float.intBitsToFloat(vertices[at]),
                                Float.intBitsToFloat(vertices[at + 1]), Float.intBitsToFloat(vertices[at + 2])));
                        minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
                        maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
                    }
                }
            }
            if (!Double.isFinite(minX + maxY)) return new Placement(new GroundLootFlatPose(0, 0, 0, 0, 0),
                    new AABB(-.25, 0, -.25, .25, .4, .25));
            return placement(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        });
    }

    private static Placement placement(AABB geometry) {
        double w = geometry.getXsize(), h = geometry.getYsize(), d = geometry.getZsize();
        var flat = GroundLootFlatPose.fromBounds(geometry.minX, geometry.minY, geometry.minZ,
                Math.max(geometry.minX + 1e-6, geometry.maxX), Math.max(geometry.minY + 1e-6, geometry.maxY),
                Math.max(geometry.minZ + 1e-6, geometry.maxZ));
        if (flat.zRotationDegrees() != 0) { double old = w; w = h; h = old; }
        else if (flat.xRotationDegrees() != 0) { double old = h; h = d; d = old; }
        return new Placement(flat, new AABB(-w / 2, 0, -d / 2, w / 2, Math.max(.004, h), d / 2));
    }

    private record Placement(GroundLootFlatPose flat, AABB bounds) {}
}
