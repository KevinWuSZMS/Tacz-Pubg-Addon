package com.example.examplemod.visibility.mixin;

import com.example.examplemod.visibility.client.FarTargetRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Record successful native BE drawing without suppressing or taking over any renderer. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityDispatcherVisibilityMixin {
    @Inject(method = "setupAndRender", at = @At("RETURN"))
    private static void pubg$recordDraw(BlockEntityRenderer<?> renderer, BlockEntity target, float partialTick,
            PoseStack pose, MultiBufferSource buffers, CallbackInfo callback) {
        FarTargetRenderer.nativeRendered(target);
    }
}
