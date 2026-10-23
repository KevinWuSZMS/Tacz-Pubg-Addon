package com.example.examplemod.lootspawn.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.model.BedrockAmmoModel;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ammo and attachments retain their native dropped-item GROUND size, including thin models. */
final class GroundSupplementModelRenderer {
    private static final Map<Model, Map<BakedModel, Placement>> CACHE = new HashMap<>();
    private GroundSupplementModelRenderer() {}

    static void clearCache() { CACHE.clear(); }

    static AABB bounds(ItemStack stack, BakedModel baked) {
        Model source = source(stack);
        return source == null ? GroundLootIconRenderer.bounds(baked) : placement(source, baked).bounds;
    }

    static boolean render(ItemStack stack, BakedModel baked, PoseStack poses, MultiBufferSource buffers, int light) {
        Model source = source(stack);
        if (source == null) return false;
        Placement placement = placement(source, baked);
        if (placement.matrix == null) return false;
        poses.pushPose();
        try {
            var flat = placement.flat;
            poses.translate(flat.offsetX(), flat.offsetY(), flat.offsetZ());
            if (flat.xRotationDegrees() != 0) poses.mulPose(Axis.XP.rotationDegrees(flat.xRotationDegrees()));
            if (flat.zRotationDegrees() != 0) poses.mulPose(Axis.ZP.rotationDegrees(flat.zRotationDegrees()));
            poses.mulPose(placement.matrix);
            VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(source.texture));
            for (BedrockPart part : source.model.getShouldRender()) collect(source.assetId, part, poses, vertices, light);
        } finally {
            poses.popPose();
        }
        return true;
    }

    private static Model source(ItemStack stack) {
        if (stack.getItem() instanceof IAmmo ammo) {
            var index = TimelessAPI.getClientAmmoIndex(ammo.getAmmoId(stack)).orElse(null);
            if (index == null || index.getAmmoModel() == null || index.getModelTextureLocation() == null) return null;
            return new Model(ammo.getAmmoId(stack).toString(), index.getAmmoModel(), index.getModelTextureLocation(),
                    index.getTransform().getScale().getGround(), index.getAmmoModel().getGroundOriginPath());
        }
        if (stack.getItem() instanceof IAttachment attachment) {
            var index = TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(stack)).orElse(null);
            if (index == null || index.getAttachmentModel() == null || index.getModelTexture() == null) return null;
            return new Model(attachment.getAttachmentId(stack).toString(), index.getAttachmentModel(), index.getModelTexture(), null, null);
        }
        return null;
    }

    private static Placement placement(Model source, BakedModel baked) {
        return CACHE.computeIfAbsent(source, ignored -> new IdentityHashMap<>()).computeIfAbsent(baked, ignored -> safeMeasure(source, baked));
    }

    private static Placement safeMeasure(Model source, BakedModel baked) {
        try {
            PoseStack poses = new PoseStack();
            ClientHooks.handleCameraTransforms(poses, baked, ItemDisplayContext.GROUND, false);
            poses.translate(-.5, -.5, -.5);
            poses.translate(.5, 2, .5);
            poses.scale(-1, -1, 1);
            if (source.model instanceof BedrockAmmoModel) {
                GroundGunModelRenderer.applyGroundOrigin(poses, source.origin, source.scale);
                if (source.scale != null) {
                    poses.translate(0, 1.5, 0); poses.scale(source.scale.x(), source.scale.y(), source.scale.z()); poses.translate(0, -1.5, 0);
                }
            }
            GroundLootMeshBounds vertices = new GroundLootMeshBounds();
            for (BedrockPart part : source.model.getShouldRender()) collect(source.assetId, part, poses, vertices, 0);
            double w = vertices.maxX - vertices.minX, h = vertices.maxY - vertices.minY, d = vertices.maxZ - vertices.minZ;
            GroundLootFlatPose flat = GroundLootFlatPose.fromBounds(vertices.minX, vertices.minY, vertices.minZ,
                    Math.max(vertices.minX + 1e-6, vertices.maxX), Math.max(vertices.minY + 1e-6, vertices.maxY),
                    Math.max(vertices.minZ + 1e-6, vertices.maxZ));
            if (flat.zRotationDegrees() != 0) { double old = w; w = h; h = old; }
            else if (flat.xRotationDegrees() != 0) { double old = h; h = d; d = old; }
            return new Placement(new Matrix4f(poses.last().pose()), flat,
                    new AABB(-w / 2, 0, -d / 2, w / 2, Math.max(.004, h), d / 2));
        } catch (RuntimeException invalidModel) {
            return new Placement(null, null, GroundLootIconRenderer.bounds(baked));
        }
    }

    private static void collect(String assetId, BedrockPart part, PoseStack poses, VertexConsumer vertices, int light) {
        GroundGunModelRenderer.collectSupplement(assetId, part, poses, vertices, light);
    }

    private record Model(String assetId, BedrockModel model, ResourceLocation texture, Vector3f scale, List<BedrockPart> origin) {}
    private record Placement(Matrix4f matrix, GroundLootFlatPose flat, AABB bounds) {}
}
