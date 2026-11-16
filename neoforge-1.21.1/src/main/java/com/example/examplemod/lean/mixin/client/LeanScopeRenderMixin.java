package com.example.examplemod.lean.mixin.client;

import java.util.List;

import com.example.examplemod.lean.client.LeanGunRender;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = BedrockAttachmentModel.class, remap = false)
public abstract class LeanScopeRenderMixin {
    @ModifyReturnValue(method = "getBedrockPartCenter", at = @At("RETURN"), remap = false)
    private Vector3f taczPubg$nativeApertureCenter(Vector3f center, PoseStack pose, List<BedrockPart> path) {
        return LeanGunRender.nativeMaskCenter(pose, center);
    }

    @ModifyExpressionValue(method = {"renderBothAccelerated", "renderSightAccelerated", "renderScopeAccelerated"},
            at = @At(value = "NEW", target = "com/mojang/blaze3d/vertex/PoseStack"), require = 3, remap = false)
    private PoseStack taczPubg$carryDeferredLean(PoseStack copy, PoseStack source,
            ItemDisplayContext context, RenderType type, int light, int overlay) {
        LeanGunRender.copy(source, copy);
        return copy;
    }
}
