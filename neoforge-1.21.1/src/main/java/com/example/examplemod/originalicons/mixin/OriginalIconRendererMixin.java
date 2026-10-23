package com.example.examplemod.originalicons.mixin;

import com.example.examplemod.originalicons.client.OriginalIconClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public abstract class OriginalIconRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void pubg$inventoryIcon(ItemStack stack, ItemDisplayContext context, boolean leftHand,
            PoseStack pose, MultiBufferSource buffers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (OriginalIconClient.render(stack, context, pose, buffers)) ci.cancel();
        else if (com.example.examplemod.originalicons.client.DetailedItemRenderer.render(
                stack, context, leftHand, pose, buffers, light, overlay, model)) ci.cancel();
    }
}
