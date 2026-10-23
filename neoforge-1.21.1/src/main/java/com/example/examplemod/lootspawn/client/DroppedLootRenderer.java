package com.example.examplemod.lootspawn.client;

import com.example.examplemod.lootspawn.DroppedLootPolicy;
import com.example.examplemod.lootspawn.GroundLootFootprints;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** TaCZ drops follow normal item physics while using the exact stationary loot mesh. */
public final class DroppedLootRenderer extends ItemEntityRenderer {
    private final ItemRenderer itemRenderer;

    public DroppedLootRenderer(EntityRendererProvider.Context context) {
        super(context);
        itemRenderer = context.getItemRenderer();
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityType.ITEM, DroppedLootRenderer::new);
    }

    @Override
    public void render(ItemEntity entity, float entityYaw, float partialTicks, PoseStack poses,
            MultiBufferSource buffers, int packedLight) {
        if (!DroppedLootPolicy.isConvertible(entity.getItem(), entity.level())) {
            super.render(entity, entityYaw, partialTicks, poses, buffers, packedLight);
            return;
        }
        if (DroppedLootTransitionClient.shouldHideDropped(entity)) return;
        DroppedLootTransitionClient.noteFrame(entity, partialTicks);
        // The dispatcher already interpolated flight position. Deliberately omit vanilla
        // getSpin(), sine bobbing and count-dependent copies/offsets: conversion keeps
        // this same single mesh, yaw and floor lift at exactly the landing position.
        GroundLootRenderer.renderStack(entity.getItem(), entity.level(), entity.getId(),
                DroppedLootPolicy.yaw(entity), itemRenderer, poses, buffers, packedLight);
    }

    @Override
    protected float getShadowRadius(ItemEntity entity) {
        return DroppedLootPolicy.isConvertible(entity.getItem(), entity.level()) ? 0 : super.getShadowRadius(entity);
    }

    @Override
    public boolean shouldRender(ItemEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        if (!DroppedLootPolicy.isConvertible(entity.getItem(), entity.level())) {
            return super.shouldRender(entity, frustum, camX, camY, camZ);
        }
        // Use the full-size model's range and bounds, just as GroundLootEntity does.
        // ItemEntity's small carrier box otherwise clips rifles near the screen edge.
        AABB bounds = GroundLootFootprints.worldBounds(GroundLootFootprints.forItem(entity.getItem(), entity.level()),
                entity.position(), DroppedLootPolicy.yaw(entity));
        // Visibility follows the model, while collision deliberately follows the
        // small carrier. A protruding barrel must not hide the entire item.
        double size = bounds.getSize();
        if (!Double.isFinite(size) || size <= 0) size = 1;
        double range = size * 64 * Entity.getViewScale();
        double dx = entity.getX() - camX, dy = entity.getY() - camY, dz = entity.getZ() - camZ;
        return dx * dx + dy * dy + dz * dz < range * range
                && (entity.noCulling || frustum.isVisible(bounds.inflate(.5)));
    }
}
