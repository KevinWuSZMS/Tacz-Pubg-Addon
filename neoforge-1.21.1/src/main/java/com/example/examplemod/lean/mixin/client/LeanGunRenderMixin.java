package com.example.examplemod.lean.mixin.client;

import com.example.examplemod.lean.client.LeanGunRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.renderer.item.GunItemRendererWrapper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GunItemRendererWrapper.class, remap = false)
public abstract class LeanGunRenderMixin {
    @Inject(method = "renderFirstPersonInner", at = @At("RETURN"), remap = false)
    private void taczPubg$clearLeanFrame(LocalPlayer player, ItemStack stack, ItemDisplayContext context,
            PoseStack pose, MultiBufferSource buffers, int light, float partialTick, CallbackInfo callback) {
        LeanGunRender.clear(pose);
    }
}
