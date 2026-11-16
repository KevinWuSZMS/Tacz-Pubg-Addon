package com.example.examplemod.lean.mixin.client;

import com.example.examplemod.lean.client.LeanGunRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.event.FirstPersonRenderGunEvent;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FirstPersonRenderGunEvent.class, remap = false)
public abstract class LeanFirstPersonRenderMixin {
    @Inject(method = "applyFirstPersonGunTransform(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/tacz/guns/client/model/BedrockGunModel;F)V",
            at = @At("TAIL"), remap = false)
    private static void taczPubg$leanAfterAds(LocalPlayer player, ItemStack stack, PoseStack pose,
            BedrockGunModel model, float partialTick, CallbackInfo callback) {
        LeanGunRender.apply(player, pose, partialTick);
    }
}
