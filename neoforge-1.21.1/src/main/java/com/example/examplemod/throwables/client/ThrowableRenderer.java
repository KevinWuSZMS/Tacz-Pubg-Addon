package com.example.examplemod.throwables.client;

import com.example.examplemod.throwables.PubgThrowableEntity;
import com.example.examplemod.throwables.ThrowableKind;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** The existing PUBG blue-zone field remains distinct from LR's native smoke renderer. */
public final class ThrowableRenderer extends EntityRenderer<PubgThrowableEntity> {
    private static final RenderType VOLUME = RenderType.create("tacz_pubg_throwable_volume",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 32768, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    private final ThrownItemRenderer<PubgThrowableEntity> item;
    public ThrowableRenderer(EntityRendererProvider.Context context) {
        super(context); item = new ThrownItemRenderer<>(context, .75f, false);
    }
    @Override public void render(PubgThrowableEntity entity, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light) {
        if (!entity.field()) { item.render(entity, yaw, partial, pose, buffers, light); return; }
        if (entity.kind() != ThrowableKind.BLUE_ZONE) return;
        double radius = entity.fieldRadius();
        if (radius <= .02) return;
        VertexConsumer vertices = buffers.getBuffer(VOLUME);
        pose.pushPose();
        var matrix = pose.last().pose();
        for (int layer = 0; layer < 1; layer++) {
            double shell = radius * (1 - layer * .18);
            for (int lat = 0; lat < 8; lat++) for (int lon = 0; lon < 20; lon++) {
                double a = Math.PI * lat / 8 - Math.PI / 2, b = Math.PI * (lat + 1) / 8 - Math.PI / 2;
                double c = Math.PI * 2 * lon / 20, d = Math.PI * 2 * (lon + 1) / 20;
                Vec3 p1 = sphere(shell, a, c), p2 = sphere(shell, a, d), p3 = sphere(shell, b, d), p4 = sphere(shell, b, c);
                int alpha = 45, red = 45, green = 130, blue = 255;
                for (Vec3 point : new Vec3[] {p1, p2, p3, p4})
                    vertices.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z).setColor(red, green, blue, alpha);
            }
        }
        pose.popPose();
    }
    private static Vec3 sphere(double radius, double latitude, double longitude) {
        return new Vec3(radius * Math.cos(latitude) * Math.cos(longitude), radius * Math.sin(latitude),
                radius * Math.cos(latitude) * Math.sin(longitude));
    }
    @Override public ResourceLocation getTextureLocation(PubgThrowableEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png");
    }
}
