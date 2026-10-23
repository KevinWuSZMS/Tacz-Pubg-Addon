package com.example.examplemod.lootspawn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/** Draw the original texture itself, never recursively invoke a custom item renderer. */
final class GroundLootIconRenderer {
    private static final Map<BakedModel, AABB> CACHE = new IdentityHashMap<>();
    private GroundLootIconRenderer() {}
    static void clearCache() { CACHE.clear(); }

    /** TaCZ's native SlotModel is a unit XY quad; preserve the item's actual GROUND transform. */
    static AABB bounds(BakedModel baked) {
        return CACHE.computeIfAbsent(baked, model -> {
            PoseStack poses = new PoseStack();
            ClientHooks.handleCameraTransforms(poses, model, ItemDisplayContext.GROUND, false);
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (float x : new float[]{-.5F, .5F}) for (float y : new float[]{-.5F, .5F}) {
                Vector3f point = poses.last().pose().transformPosition(new Vector3f(x, y, 0));
                minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y()); minZ = Math.min(minZ, point.z());
                maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y()); maxZ = Math.max(maxZ, point.z());
            }
            var flat = GroundLootFlatPose.fromBounds(minX, minY, minZ, Math.max(minX + 1e-6, maxX),
                    Math.max(minY + 1e-6, maxY), Math.max(minZ + 1e-6, maxZ));
            double width = maxX - minX, height = maxY - minY, depth = maxZ - minZ;
            if (flat.zRotationDegrees() != 0) { double old = width; width = height; height = old; }
            else if (flat.xRotationDegrees() != 0) { double old = height; height = depth; depth = old; }
            return new AABB(-width / 2, 0, -depth / 2, width / 2, Math.max(.025, height), depth / 2);
        });
    }

    static void texture(ResourceLocation texture, BakedModel baked, PoseStack poses, MultiBufferSource buffers, int light) {
        AABB nativeSize = bounds(baked);
        draw(texture, 0, 0, 1, 1, poses, buffers, light, (float) nativeSize.getXsize(), (float) nativeSize.getZsize());
    }

    static void sprite(TextureAtlasSprite sprite, BakedModel baked, PoseStack poses, MultiBufferSource buffers, int light) {
        AABB nativeSize = bounds(baked);
        draw(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
                poses, buffers, light, (float) nativeSize.getXsize(), (float) nativeSize.getZsize());
    }

    private static void draw(ResourceLocation texture, float u0, float v0, float u1, float v1,
            PoseStack poses, MultiBufferSource buffers, int light, float width, float depth) {
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        PoseStack.Pose pose = poses.last();
        float halfX = width / 2, halfZ = depth / 2;
        // The normal and winding face up. The icon occupies X/Z, with no upright billboard.
        vertex(vertices, pose, -halfX, -halfZ, u0, v0, light);
        vertex(vertices, pose, -halfX, halfZ, u0, v1, light);
        vertex(vertices, pose, halfX, halfZ, u1, v1, light);
        vertex(vertices, pose, halfX, -halfZ, u1, v0, light);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose,
            float x, float z, float u, float v, int light) {
        vertices.addVertex(pose, x, 0, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
    }
}
