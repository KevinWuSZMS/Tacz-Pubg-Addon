package com.example.examplemod.lootspawn.client;

import com.example.examplemod.lootspawn.GroundLootEntity;
import com.example.examplemod.lootspawn.GroundLootFootprints;
import com.example.examplemod.lootspawn.LootSpawnRegistration;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Native dropped-item GROUND sizes, flattened and recentered without an extra size multiplier. */
public final class GroundLootRenderer extends EntityRenderer<GroundLootEntity> {
    private final ItemRenderer itemRenderer;

    public GroundLootRenderer(EntityRendererProvider.Context context) {
        super(context);
        itemRenderer = context.getItemRenderer();
        shadowRadius = 0;
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LootSpawnRegistration.GROUND_LOOT.get(), GroundLootRenderer::new);
    }

    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            GroundGunModelRenderer.clearCache();
            GroundSupplementModelRenderer.clearCache();
            GroundBakedModelRenderer.clearCache();
            GroundLootIconRenderer.clearCache();
        });
    }

    @Override
    public void render(GroundLootEntity entity, float yaw, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight) {
        if (entity.isPhysicsBlocked() || DroppedLootTransitionClient.shouldHideGround(entity)) return;
        renderStack(entity.getItem(), entity.level(), entity.getId(), entity.getYRot(), itemRenderer,
                poseStack, buffers, packedLight);
    }

    /** One mesh and one placement for both flying ItemEntity and stationary GroundLootEntity. */
    static void renderStack(ItemStack stack, Level level, int seed, float yaw, ItemRenderer renderer,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        if (stack.isEmpty()) return;
        poseStack.pushPose();
        try {
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.translate(0, GroundLootFootprints.FLOOR_LIFT, 0);
            float multiplier = (float) com.example.examplemod.modelscale.ModelScaleService.dropped(level, stack);
            poseStack.scale(multiplier, multiplier, multiplier);
            var baked = renderer.getModel(stack, level, null, seed);
            if (stack.getItem() instanceof IGun) {
                GroundGunModelRenderer.render(stack, baked, poseStack, buffers, packedLight);
            } else if (stack.getItem() instanceof IAmmo ammo) {
                if (!GroundSupplementModelRenderer.render(stack, baked, poseStack, buffers, packedLight)) {
                    TimelessAPI.getClientAmmoIndex(ammo.getAmmoId(stack)).ifPresentOrElse(
                            index -> GroundLootIconRenderer.texture(index.getSlotTextureLocation(), baked, poseStack, buffers, packedLight),
                            () -> GroundLootIconRenderer.sprite(baked.getParticleIcon(ModelData.EMPTY), baked, poseStack, buffers, packedLight));
                }
            } else if (stack.getItem() instanceof IAttachment attachment) {
                if (!GroundSupplementModelRenderer.render(stack, baked, poseStack, buffers, packedLight)) {
                    TimelessAPI.getClientAttachmentIndex(attachment.getAttachmentId(stack)).ifPresentOrElse(
                            index -> GroundLootIconRenderer.texture(index.getSlotTexture(), baked, poseStack, buffers, packedLight),
                            () -> GroundLootIconRenderer.sprite(baked.getParticleIcon(ModelData.EMPTY), baked, poseStack, buffers, packedLight));
                }
            } else {
                GroundBakedModelRenderer.render(stack, baked, renderer, poseStack, buffers, packedLight);
            }
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public ResourceLocation getTextureLocation(GroundLootEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
