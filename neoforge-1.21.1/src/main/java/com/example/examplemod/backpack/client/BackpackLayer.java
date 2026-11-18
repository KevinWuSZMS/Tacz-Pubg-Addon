package com.example.examplemod.backpack.client;

import com.example.examplemod.backpack.BackpackItem;
import com.example.examplemod.zones.match.flight.ParachuteItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.item.ItemStack;

public final class BackpackLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    public BackpackLayer(PlayerRenderer renderer) { super(renderer); }
    @Override public void render(PoseStack pose, MultiBufferSource buffer, int light, AbstractClientPlayer player,
            float limbSwing, float limbSwingAmount, float partialTick, float age, float yaw, float pitch) {
        ItemStack stack = com.example.examplemod.backpack.BackpackService.equipped(player);
        if (!(stack.getItem() instanceof BackpackItem || stack.getItem() instanceof ParachuteItem)
                || player.isInvisible() || player.isSpectator()) return;
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        com.example.examplemod.originalicons.client.WearableItemRenderer.render(stack, true, player, pose, buffer, light);
        pose.popPose();
    }
}
