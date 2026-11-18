package com.example.examplemod.inventory.client;

import com.example.examplemod.inventory.mixin.InventoryEntityRenderDispatcherAccessor;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The vanilla player renderer, bounded for a large preview and isolated from live player state. */
public final class PubgInventoryCharacterRenderer {
    private PubgInventoryCharacterRenderer() { }

    /** Bounds and mouse use logical inventory coordinates; graphics already carries viewScale. */
    public static void render(GuiGraphics graphics, int left, int top, int right, int bottom,
                              float viewScale, double mouseX, double mouseY, LocalPlayer player) {
        if (player == null || right <= left || bottom <= top || !Float.isFinite(viewScale) || viewScale <= 0) return;
        float entityScale = player.getScale();
        var preview = PubgInventoryCharacterPose.of(left, top, right, bottom, mouseX, mouseY,
                player.getBbHeight() / entityScale);
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        var camera = dispatcher.cameraOrientation();
        boolean shadow = ((InventoryEntityRenderDispatcherAccessor) dispatcher).taczPubg$shouldRenderShadow();
        boolean hitBoxes = dispatcher.shouldRenderHitBoxes();
        var angles = PlayerAngles.capture(player);
        PoseStack.Pose previousPose = graphics.pose().last();
        graphics.flush();
        graphics.pose().pushPose();
        // The vanilla helper uses physical GUI coordinates for both its scissor and entity scale.
        graphics.pose().scale(1 / viewScale, 1 / viewScale, 1);
        graphics.pose().translate(0, 0, PubgInventoryCharacterPose.DEPTH_OFFSET);
        graphics.enableScissor(Math.round(left * viewScale), Math.round(top * viewScale),
                Math.round(right * viewScale), Math.round(bottom * viewScale));
        try {
            float yaw = 180 + preview.angleX() * 40;
            player.yBodyRot = player.yBodyRotO = 180 + preview.angleX() * 20;
            player.setYRot(yaw);
            player.yRotO = player.yHeadRot = player.yHeadRotO = yaw;
            player.setXRot(-preview.angleY() * 20);
            player.xRotO = player.getXRot();
            dispatcher.setRenderHitBoxes(false);
            Quaternionf tilt = new Quaternionf().rotateX(preview.angleY() * 20 * (float) (Math.PI / 180));
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI).mul(tilt);
            // Native Z=50 alone is too shallow for a full-height tilted model: its head can fall
            // behind the inventory background's Z=0 depth. The additional depth keeps it in front.
            InventoryScreen.renderEntityInInventory(graphics,
                    (left + right) / 2f * viewScale, (top + bottom) / 2f * viewScale,
                    preview.scale() * viewScale / entityScale,
                    new Vector3f(0, player.getBbHeight() / 2, 0), pose, tilt, player);
        } finally {
            angles.restore(player);
            dispatcher.overrideCameraOrientation(camera);
            dispatcher.setRenderShadow(shadow);
            dispatcher.setRenderHitBoxes(hitBoxes);
            graphics.disableScissor();
            // Also unwind the native helper's push if a renderer throws before its own pop.
            while (graphics.pose().last() != previousPose) graphics.pose().popPose();
            Lighting.setupFor3DItems();
        }
    }

    private record PlayerAngles(float body, float oldBody, float yaw, float oldYaw,
                                float pitch, float oldPitch, float head, float oldHead) {
        static PlayerAngles capture(LocalPlayer player) {
            return new PlayerAngles(player.yBodyRot, player.yBodyRotO, player.getYRot(), player.yRotO,
                    player.getXRot(), player.xRotO, player.yHeadRot, player.yHeadRotO);
        }
        void restore(LocalPlayer player) {
            player.yBodyRot = body; player.yBodyRotO = oldBody;
            player.setYRot(yaw); player.yRotO = oldYaw;
            player.setXRot(pitch); player.xRotO = oldPitch;
            player.yHeadRot = head; player.yHeadRotO = oldHead;
        }
    }
}
