package com.example.examplemod.visibility.client;

import com.example.examplemod.visibility.VisibilityPolicy;
import com.example.examplemod.visibility.TargetRenderLedger;
import com.tacz.guns.block.entity.TargetBlockEntity;
import java.util.HashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Reuses TaCZ's actual renderer with server light; depth and frustum remain enabled. */
public final class FarTargetRenderer {
    private static final TargetRenderLedger DRAWN = new TargetRenderLedger();
    private FarTargetRenderer() {}
    static void register() { NeoForge.EVENT_BUS.addListener(FarTargetRenderer::render); }
    static void clear() { DRAWN.clear(); }
    public static void nativeRendered(BlockEntity target) {
        if (target instanceof TargetBlockEntity && target.getLevel() == Minecraft.getInstance().level)
            DRAWN.record(target.getBlockPos().asLong());
    }
    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            var keys = new HashSet<Long>();
            for (var pos : VisibilityClient.targets().keySet()) keys.add(pos.asLong());
            DRAWN.begin(keys);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        DRAWN.finish();
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        for (var replica : VisibilityClient.targets().values()) {
            var target = replica.target();
            var pos = target.getBlockPos();
            if (!VisibilityPolicy.inRange(pos.getCenter().distanceToSqr(camera), VisibilityClient.settings().targets())) continue;
            // Cached terrain/BE existence does not prove that Sodium had a compiled section to draw.
            // Skip only a target whose native renderer actually completed during this frame's BE phase.
            if (DRAWN.wasRendered(pos.asLong())) continue;
            if (!event.getFrustum().isVisible(new AABB(pos).inflate(2))) continue;
            BlockEntityRenderer<TargetBlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(target);
            if (renderer == null) continue;
            pose.pushPose();
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            renderer.render(target, event.getPartialTick().getGameTimeDeltaPartialTick(false), pose, buffers,
                    replica.packedLight(), OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }
}
