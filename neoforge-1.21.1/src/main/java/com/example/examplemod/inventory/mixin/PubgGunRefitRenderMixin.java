package com.example.examplemod.inventory.mixin;

import com.example.examplemod.inventory.client.PubgGunRefitScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.event.FirstPersonRenderGunEvent;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the native refit renderer and camera animations, with a smaller presentation in our screen. */
@Mixin(value = FirstPersonRenderGunEvent.class, remap = false)
abstract class PubgGunRefitRenderMixin {
    @Inject(method = "applyFirstPersonGunTransform", at = @At("TAIL"), remap = false)
    private static void taczPubg$scaleRefitPreview(LocalPlayer player, ItemStack gun, PoseStack poses,
                                                 BedrockGunModel model, float partialTick, CallbackInfo callback) {
        if (!(Minecraft.getInstance().screen instanceof PubgGunRefitScreen)) return;

        // Contract camera X/Y, leaving depth unchanged: scaling XYZ about the eye would cancel
        // in perspective division. Conjugation also preserves any active camera/model-view pose.
        Matrix4f view = RenderSystem.getModelViewMatrix();
        Matrix4f presentation = new Matrix4f(view).invert().scale(.8f, .8f, 1).mul(view);
        poses.last().pose().mulLocal(presentation);
        // This is a projection-like presentation transform, so native lighting normals stay intact.
        // TaCZ owns the surrounding push/pop; neither normal play nor another refit screen inherits it.
    }
}
