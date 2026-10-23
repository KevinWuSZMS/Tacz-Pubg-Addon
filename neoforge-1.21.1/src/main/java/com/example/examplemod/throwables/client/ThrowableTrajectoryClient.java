package com.example.examplemod.throwables.client;

import com.example.examplemod.throwables.ThrowableBallistics;
import com.example.examplemod.throwables.ThrowableKind;
import com.example.examplemod.throwables.ThrowableService;
import com.example.examplemod.throwables.ThrowableTrajectoryProbe;
import com.example.examplemod.vitals.client.MatchVitalsHud;
import com.example.examplemod.zones.client.MapOverlayClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/** Depth-tested world geometry, with no HUD text and no server/world-side prediction effects. */
final class ThrowableTrajectoryClient {
    private static final RenderType ARC = RenderType.create("tacz_pubg_throwable_trajectory",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 32768, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .createCompositeState(false));
    private static ThrowableTrajectoryProbe probe;
    private static ThrowableTrajectoryProbe.Prediction prediction;
    private static ThrowableClient.Aim lastAim;
    private static int lastTick = -1;
    private static float lastPitch, lastYaw;
    private static boolean lastLow, registered;
    private static Vec3 lastEye;
    private static Vec3 c4Origin;
    private static BlockHitResult c4Placement;
    private static int c4UseTicks;
    private record RenderView(Vec3 camera, Vec3 right, Vec3 up) {}
    private ThrowableTrajectoryClient() {}

    static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ThrowableTrajectoryClient::render);
    }
    static void clear() {
        probe = null; prediction = null; lastAim = null; lastTick = -1; lastEye = null;
        c4Origin = null; c4Placement = null; c4UseTicks = 0;
    }
    private static void refresh(ThrowableClient.Aim aim, float partialTick) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        // LocalPlayer supplies this frame's mouse rotation directly. Position must use the same
        // partial tick as the camera; caching either for 33 ms made the arc jerk while turning
        // or moving. Reuse only identical launches within the same world tick, never blend paths
        // across a wall hit or yaw wrap (that would display a launch that cannot actually occur).
        Vec3 eye = player.getEyePosition(partialTick);
        float pitch = player.getViewXRot(partialTick), yaw = player.getViewYRot(partialTick);
        if (aim.equals(lastAim) && player.tickCount == lastTick && eye.equals(lastEye)
                && pitch == lastPitch && yaw == lastYaw && player.isShiftKeyDown() == lastLow) return;
        lastAim = aim; lastTick = player.tickCount; lastEye = eye; lastPitch = pitch; lastYaw = yaw;
        lastLow = player.isShiftKeyDown();
        Vec3 look = player.calculateViewVector(pitch, yaw);
        prediction = null;
        if (aim.kind() == ThrowableKind.C4) {
            if (!player.onGround() || player.isPassenger()
                    || !ThrowableTrajectoryProbe.loadedSegment(mc.level, eye,
                        eye.add(look.scale(ThrowableBallistics.placementReach())))) return;
            var hit = ThrowableBallistics.placement(player, eye, look);
            int useTicks = player.getTicksUsingItem();
            if (c4Origin == null || useTicks < c4UseTicks) {
                c4Origin = player.position(); c4Placement = hit;
            }
            c4UseTicks = useTicks;
            if (hit != null && c4Placement != null && player.position().distanceToSqr(c4Origin) <= .01
                    && hit.getBlockPos().equals(c4Placement.getBlockPos())
                    && hit.getLocation().distanceToSqr(c4Placement.getLocation()) <= .25) {
                var normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
                prediction = visiblePrefix(new ThrowableTrajectoryProbe.Prediction(
                        List.of(new ThrowableTrajectoryProbe.Point(hit.getLocation().add(normal.scale(.06)), normal, true)),
                        ThrowableTrajectoryProbe.End.ATTACHED), eye);
            }
            return;
        }
        c4Origin = null; c4Placement = null; c4UseTicks = 0;
        if (!ThrowableTrajectoryProbe.loadedSegment(mc.level, eye, eye.add(look.scale(.25)))) return;
        if (probe == null || probe.level() != mc.level) probe = new ThrowableTrajectoryProbe(mc.level);
        probe.reset(aim.kind(), player, aim.fuse(), entity -> entity == player || !entity.isInvisibleTo(player)
                && visible(eye, entity.getBoundingBox().getCenter())
                && !ThrowableService.blocksSight(mc.level, eye, entity.getBoundingBox().getCenter()), eye, pitch, yaw);
        var map = MapOverlayClient.current();
        boolean inMatchMap = MatchVitalsHud.active() && MapOverlayClient.isMatchRunning() && map != null
                && map.contains(player.getX(), player.getZ());
        probe.setScope(point -> !inMatchMap || map.contains(point.x, point.z));
        prediction = visiblePrefix(probe.predict(), eye);
    }
    private static ThrowableTrajectoryProbe.Prediction visiblePrefix(ThrowableTrajectoryProbe.Prediction raw, Vec3 eye) {
        if (raw.points().isEmpty()) return null;
        var visible = new ArrayList<ThrowableTrajectoryProbe.Point>();
        Vec3 previous = raw.points().getFirst().position();
        if (!visible(eye, previous)) return null;
        visible.add(raw.points().getFirst());
        for (int i = 1; i < raw.points().size(); i++) {
            var point = raw.points().get(i);
            if (!visible(eye, point.position()) || !visible(eye, previous.lerp(point.position(), .5)))
                return new ThrowableTrajectoryProbe.Prediction(List.copyOf(visible), ThrowableTrajectoryProbe.End.LIMIT);
            visible.add(point);
            previous = point.position();
        }
        return new ThrowableTrajectoryProbe.Prediction(List.copyOf(visible), raw.end());
    }
    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { clear(); return; }
        var aim = ThrowableClient.trajectoryAim();
        if (aim == null) {
            prediction = null; lastAim = null; c4Origin = null; c4Placement = null; c4UseTicks = 0;
            return;
        }
        refresh(aim, event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (prediction == null || prediction.points().isEmpty()) return;
        // AFTER_LEVEL follows shader-pack compositing, so explicitly use this frame's camera
        // matrices and scene target. The custom RenderType retains LEQUAL depth testing.
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        float[] oldColor = RenderSystem.getShaderColor().clone();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity().mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(event.getProjectionMatrix()), VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        mc.getMainRenderTarget().bindWrite(false);
        var buffers = mc.renderBuffers().bufferSource();
        try {
            var vertices = buffers.getBuffer(ARC);
            var camera = event.getCamera();
            var left = camera.getLeftVector();
            var up = camera.getUpVector();
            draw(vertices, new PoseStack(), new RenderView(camera.getPosition(),
                    new Vec3(-left.x(), -left.y(), -left.z()), new Vec3(up.x(), up.y(), up.z())), aim);
            buffers.endBatch(ARC);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
        }
    }
    private static void draw(VertexConsumer vertices, PoseStack poses, RenderView view, ThrowableClient.Aim aim) {
        var points = prediction.points();
        int color = aim.fuse() > 0 && aim.fuse() <= 20 && aim.kind() != ThrowableKind.C4 ? 0xEFFF8E62 : 0xDFF6D685;
        Vec3 previous = points.getFirst().position();
        int impactMarkers = 0;
        for (int i = 1; i < points.size(); i++) {
            var point = points.get(i);
            Vec3 current = point.position();
            line(vertices, poses, view, previous, current, color);
            if (point.impact() && impactMarkers++ < 3)
                ring(vertices, poses, view, current, point.normal(), .12, 0xBFF6D685);
            previous = current;
        }
        if (prediction.end() != ThrowableTrajectoryProbe.End.LIMIT) {
            var endpoint = points.getLast();
            ring(vertices, poses, view, endpoint.position(), endpoint.normal(), .28, color);
        }
    }
    private static boolean visible(Vec3 eye, Vec3 point) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !ThrowableTrajectoryProbe.loadedSegment(mc.level, eye, point)) return false;
        var hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(point) < .0025;
    }
    private static void ring(VertexConsumer out, PoseStack pose, RenderView view, Vec3 center, Vec3 normal, double radius, int color) {
        Vec3 n = normal.lengthSqr() < .001 ? new Vec3(0, 1, 0) : normal.normalize();
        Vec3 u = n.cross(Math.abs(n.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 v = n.cross(u).normalize();
        Vec3 offset = center.add(n.scale(.015));
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24, b = Math.PI * 2 * (i + 1) / 24;
            Vec3 start = offset.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
            Vec3 finish = offset.add(u.scale(Math.cos(b) * radius)).add(v.scale(Math.sin(b) * radius));
            line(out, pose, view, start, finish, color);
        }
    }
    private static void line(VertexConsumer out, PoseStack pose, RenderView view, Vec3 start, Vec3 finish, int color) {
        Vec3 segment = finish.subtract(start);
        if (segment.lengthSqr() < 1e-9) return;
        Vec3 middle = start.lerp(finish, .5);
        Vec3 camera = view.camera();
        Vec3 side = ThrowableTrajectoryGeometry.ribbonSide(segment, middle.subtract(camera), view.right(), view.up())
                .scale(Math.clamp(camera.distanceTo(middle) * .0009, .012, .045));
        vertex(out, pose, start.add(side).subtract(camera), color);
        vertex(out, pose, start.subtract(side).subtract(camera), color);
        vertex(out, pose, finish.subtract(side).subtract(camera), color);
        vertex(out, pose, finish.add(side).subtract(camera), color);
    }
    private static void vertex(VertexConsumer out, PoseStack pose, Vec3 point, int color) {
        out.addVertex(pose.last().pose(), (float) point.x, (float) point.y, (float) point.z).setColor(color);
    }
}
