package com.example.examplemod.gunbalance.mixin;

import com.example.examplemod.gunbalance.client.UniversalScopeAnchorAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.event.FirstPersonRenderGunEvent;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FirstPersonRenderGunEvent.class, remap = false)
public abstract class FirstPersonScopePositionMixin {
    @Inject(method = "applyFirstPersonPositioningTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/tacz/guns/client/model/BedrockGunModel;Lnet/minecraft/world/item/ItemStack;FF)V",
            at = @At("HEAD"), remap = false)
    private static void taczPubg$prepareAimingAnchor(PoseStack pose, BedrockGunModel model,
            ItemStack stack, float aiming, float refit, CallbackInfo callback) {
        ((UniversalScopeAnchorAccess) model).taczPubg$prepareScopeAnchor(stack);
    }

    @Inject(method = "applyFirstPersonPositioningTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/tacz/guns/client/model/BedrockGunModel;Lnet/minecraft/world/item/ItemStack;FF)V",
            at = @At("RETURN"), remap = false)
    private static void taczPubg$clearAimingAnchor(PoseStack pose, BedrockGunModel model,
            ItemStack stack, float aiming, float refit, CallbackInfo callback) {
        ((UniversalScopeAnchorAccess) model).taczPubg$restoreScopeAnchor();
    }
}
